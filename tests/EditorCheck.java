package check;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.ide.CopyPasteManager;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Runs in a separate IDE process using the same registered action and listener as the product. */
public final class EditorCheck implements ApplicationStarter {
    private final Application app = ApplicationManager.getApplication();
    private Project project;
    private Editor editor;
    private Document document;

    @Override
    public int getRequiredModality() {
        return NOT_IN_EDT;
    }

    private void edt(Runnable runnable) {
        app.invokeAndWait(() -> WriteIntentReadAction.run(runnable), ModalityState.nonModal());
    }

    private void expect(String name, BooleanSupplier predicate) throws InterruptedException {
        boolean[] passed = {false};
        for (int i = 0; i < 100; i++) {
            edt(() -> passed[0] = predicate.getAsBoolean());
            if (passed[0]) {
                System.out.println("PASS " + name);
                return;
            }
            Thread.sleep(100);
        }
        edt(() -> System.err.println(name + ", kind=" + editor.getEditorKind() + ", caret="
                + editor.getCaretModel().getOffset() + ", folds=" + Arrays.toString(editor.getFoldingModel().getAllFoldRegions())
                + ", text=" + document.getText()));
        throw new AssertionError(name);
    }

    private int visualLine(int line) {
        return editor.offsetToVisualPosition(document.getLineStartOffset(line)).line;
    }

    private void toggle() {
        AnAction action = ActionManager.getInstance().getAction("HideCommentLines.Toggle");
        DataContext context = SimpleDataContext.getProjectContext(project);
        action.actionPerformed(AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null));
    }

    private void load(Path path) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        edt(() -> {
            document = FileDocumentManager.getInstance().getDocument(file);
            editor = EditorFactory.getInstance().createEditor(document, project, file, false, EditorKind.MAIN_EDITOR);
        });
    }

    private void check(Path path, String original, String replacement) throws Exception {
        Files.writeString(path, original);
        load(path);
        expect(path + " hidden rows", () -> visualLine(1) == 0 && visualLine(4) == 1 && visualLine(6) == 2);
        expect("visible mixed line and literal", () -> {
            FoldingModel model = editor.getFoldingModel();
            for (int line : List.of(1, 4)) {
                if (model.isOffsetCollapsed(document.getLineStartOffset(line) + 1)
                        || model.isOffsetCollapsed(document.getLineEndOffset(line) - 1)) return false;
            }
            return Arrays.stream(model.getAllFoldRegions()).allMatch(f -> f.getPlaceholderText().isEmpty());
        });
        expect("unchanged buffer", () -> original.equals(document.getText())
                && !FileDocumentManager.getInstance().isDocumentUnsaved(document));
        edt(this::toggle);
        expect("show all", () -> visualLine(6) == 6 && editor.getFoldingModel().getAllFoldRegions().length == 0);
        FoldRegion[] foreign = {null};
        edt(() -> {
            editor.getFoldingModel().runBatchFoldingOperation(() -> {
                int start = document.getLineStartOffset(4);
                foreign[0] = editor.getFoldingModel().addFoldRegion(start, start + 2, "x");
                if (foreign[0] == null) throw new AssertionError("Test fold missing");
                foreign[0].setExpanded(false);
            });
            editor.getCaretModel().moveToOffset(document.getLineStartOffset(2) + 4);
            toggle();
        });
        expect("active comment stays editable", () -> editor.getFoldingModel().isOffsetCollapsed(1)
                && !editor.getFoldingModel().isOffsetCollapsed(document.getLineStartOffset(2) + 4));
        edt(() -> editor.getCaretModel().moveToOffset(document.getLineEndOffset(1)));
        expect("hide again after leaving the comment", () -> visualLine(4) == 1);
        edt(this::toggle);
        expect("unrelated fold retained", () -> foreign[0].isValid() && !foreign[0].isExpanded());
        edt(this::toggle);
        expect("hide before edit", () -> visualLine(4) == 1);
        edt(() -> WriteCommandAction.runWriteCommandAction(project, () ->
                document.replaceString(0, document.getLineEndOffset(0), replacement)));
        expect("edited comment becomes visible code", () -> visualLine(1) == 1 && visualLine(4) == 2);
        edt(() -> EditorFactory.getInstance().releaseEditor(editor));
        if (!Files.readString(path).equals(original)) throw new AssertionError("Unexpected save");
    }

    private void checkXmlAndPlainText(Path dir) throws Exception {
        Path path = dir.resolve("ordinary.txt");
        String text = "// literal\n/* also literal */\nend";
        Files.writeString(path, text);
        load(path);
        // Wait for a scheduled refresh on a supported file to complete in the same process.
        Editor plain = editor;
        Document plainDocument = document;
        check(dir.resolve("sample.xml"),
                "<!-- head -->\n<a/> <!-- tail -->\n  <!-- inside\n  -->\n<b test=\"// literal\"/>\n\n<!-- end -->", "<head/>");
        edt(() -> {
            if (plain.getFoldingModel().getAllFoldRegions().length != 0 || !text.equals(plainDocument.getText())) {
                throw new AssertionError("Plain text was treated as source code");
            }
            EditorFactory.getInstance().releaseEditor(plain);
        });
        System.out.println("PASS plain text");
    }

    private void checkProjectFile(Path dir) throws Exception {
        Path folder = Files.createDirectory(dir.resolve("project"));
        Path path = folder.resolve("sample.xml");
        String original = "<root>\n  <!-- internal -->\n  <child/>\n</root>\n";
        Files.writeString(path, original);
        project = ProjectManagerEx.getInstanceEx().openProject(folder, OpenProjectTask.build().asNewProject());
        if (project == null) throw new AssertionError("Project failed to open");
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        FileEditorManager manager = FileEditorManager.getInstance(project);
        Runnable open = () -> {
            editor = manager.openTextEditor(new OpenFileDescriptor(project, file), false);
            if (editor == null) throw new AssertionError("File editor failed to open");
            document = editor.getDocument();
        };
        edt(open);
        expect("project file opened with hidden comments", () -> visualLine(2) == 1);
        edt(() -> manager.closeFile(file));
        edt(open);
        expect("reopened project file", () -> visualLine(2) == 1);
        edt(this::toggle);
        expect("comments restored after reopen", () -> visualLine(2) == 2);
        edt(() -> manager.closeFile(file));
        if (!original.equals(Files.readString(path))) throw new AssertionError("Project file changed");
        edt(() -> {
            if (!ProjectManager.getInstance().closeAndDispose(project)) throw new AssertionError("Project still open");
        });
    }

    private void editorAction(String id) {
        DataContext context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor).build();
        CommandProcessor.getInstance().executeCommand(project, () ->
                EditorActionManager.getInstance().getActionHandler(id).execute(editor, null, context), id, null);
    }

    private void checkCopyPaste(Path dir) throws Exception {
        Path path = dir.resolve("copy.xml");
        String source = "<a/>\n<!-- hidden -->\n<b/>\n";
        Files.writeString(path, source);
        load(path);
        expect("comment hidden before copy", () -> visualLine(2) == 1);
        edt(() -> WriteCommandAction.runWriteCommandAction(project, () -> document.replaceString(2, 2, " ")));
        String edited = "<a />\n<!-- hidden -->\n<b/>\n";
        edt(() -> FileDocumentManager.getInstance().saveDocument(document));
        if (!edited.equals(Files.readString(path))) throw new AssertionError("Editing lost hidden text");
        expect("comment still hidden after edit and save", () -> visualLine(2) == 1);
        // Compare against pasting with folds off: the IDE may indent pasted XML.
        edt(this::toggle);
        String visiblePaste = copyToEnd(edited);
        if (!visiblePaste.startsWith(edited) || !visiblePaste.substring(edited.length()).contains("<!-- hidden -->")) {
            throw new AssertionError("Missing comment in the visible control");
        }
        edt(() -> {
            WriteCommandAction.runWriteCommandAction(project, () -> document.setText(edited));
            editor.getCaretModel().moveToOffset(0);
            toggle();
        });
        expect("comment hidden before clipboard comparison", () -> visualLine(2) == 1);
        if (!visiblePaste.equals(copyToEnd(edited))) throw new AssertionError("Folding changed the pasted text");
        System.out.println("PASS pasted block matches the visible control and contains the hidden comment");
        edt(() -> {
            editor.getSelectionModel().setSelection(0, edited.length());
            CopyPasteManager.getInstance().setContents(new StringSelection("<replacement/>\n"));
            editorAction(IdeActions.ACTION_EDITOR_PASTE);
            FileDocumentManager.getInstance().saveDocument(document);
        });
        if (!Files.readString(path).equals("<replacement/>\n" + visiblePaste.substring(edited.length()))) {
            throw new AssertionError("Paste did not replace precisely the selected range");
        }
        System.out.println("PASS replacement removes the selected hidden comment and keeps the other copy");
        edt(() -> EditorFactory.getInstance().releaseEditor(editor));
    }

    private String copyToEnd(String expectedClipboard) {
        String[] pasted = {null};
        edt(() -> {
            editor.getSelectionModel().setSelection(0, document.getTextLength());
            editorAction(IdeActions.ACTION_EDITOR_COPY);
            if (!expectedClipboard.equals(CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))) {
                throw new AssertionError("Copied text differs from its source");
            }
            editor.getSelectionModel().removeSelection();
            editor.getCaretModel().moveToOffset(document.getTextLength());
            editorAction(IdeActions.ACTION_EDITOR_PASTE);
            pasted[0] = document.getText();
        });
        return pasted[0];
    }

    private void checkGoInstall(Path dir, Path archive) throws Exception {
        Path folder = Files.createDirectory(dir.resolve("go-project"));
        Path path = folder.resolve("main.go");
        String original = """
                // package documentation
                package main

                func main() {
                    // first
                    // second
                    println("// string") // keep inline
                }
                var raw = `one
                // still a string
                two`
                """;
        Files.writeString(path, original);
        project = ProjectManagerEx.getInstanceEx().openProject(folder, OpenProjectTask.build().asNewProject());
        if (project == null) throw new AssertionError("Go project failed to open");
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        FileEditorManager manager = FileEditorManager.getInstance(project);
        edt(() -> {
            editor = manager.openTextEditor(new OpenFileDescriptor(project, file), false);
            document = editor.getDocument();
        });
        expect("Go comments visible before installation", () -> visualLine(1) == 1 && visualLine(6) == 6);
        var descriptor = com.intellij.ide.plugins.PluginDescriptorLoader.readBasicDescriptorDataFromArtifact(archive);
        edt(() -> {
            if (!com.intellij.ide.plugins.PluginInstaller.installAndLoadDynamicPlugin(archive, descriptor))
                throw new AssertionError("Restartless installation failed");
        });
        expect("Go comments hidden in the already open tab", () -> visualLine(1) == 0 && visualLine(6) == 3);
        expect("Go inline and raw strings preserved", () -> {
            FoldingModel model = editor.getFoldingModel();
            for (int line : List.of(6, 9)) {
                if (model.isOffsetCollapsed(document.getLineStartOffset(line) + 3)
                        || model.isOffsetCollapsed(document.getLineEndOffset(line) - 1)) return false;
            }
            return original.equals(document.getText()) && !FileDocumentManager.getInstance().isDocumentUnsaved(document);
        });
        edt(this::toggle);
        expect("Go comments restored", () -> visualLine(1) == 1 && visualLine(6) == 6);
        edt(this::toggle);
        expect("Go comments hidden again", () -> visualLine(6) == 3);
        edt(() -> {
            manager.closeFile(file);
            if (!ProjectManager.getInstance().closeAndDispose(project)) throw new AssertionError("Go project still open");
        });
        if (!original.equals(Files.readString(path))) throw new AssertionError("Go file changed");
    }

    @Override
    public void main(List<String> args) {
        try {
            Path dir = Path.of(args.get(1));
            String language = args.get(2);
            if (language.equals("go")) checkGoInstall(dir, Path.of(args.get(3)));
            project = ProjectManager.getInstance().getDefaultProject();
            if (language.equals("py")) {
                check(dir.resolve("sample.py"), "# head\na = 1  # tail\n  # first\n  # second\ns = '// literal'\n\n# end", "head = 1");
            } else if (!language.equals("go")) check(dir.resolve("Sample." + language),
                    "// head\nint a; // tail\n  /* inside\n   */\nString s = \"// literal\";\n\n// end",
                    "int head;");
            checkXmlAndPlainText(dir);
            checkCopyPaste(dir);
            checkProjectFile(dir);
            System.out.println("ALL PASSED");
            app.invokeLater(() -> app.exit(true, true, false), ModalityState.nonModal());
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
