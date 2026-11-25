package dev.hidecommentlines;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;

import java.util.HashMap;
import java.util.Map;

@Service(Service.Level.PROJECT)
public final class CommentView implements Disposable {
    private static final String SETTING = "dev.hidecommentlines.enabled";
    private final Project project;
    private final Map<Editor, EditorComments> sessions = new HashMap<>();

    public CommentView(Project project) {
        this.project = project;
    }

    static boolean enabled(Project project) {
        return PropertiesComponent.getInstance(project).getBoolean(SETTING, true);
    }

    static boolean isSourceEditor(Editor editor) {
        return editor.getEditorKind() == EditorKind.MAIN_EDITOR
                || (editor.getEditorKind() == EditorKind.UNTYPED && !editor.isViewer()
                && FileDocumentManager.getInstance().getFile(editor.getDocument()) != null);
    }

    void setEnabled(boolean enabled) {
        PropertiesComponent.getInstance(project).setValue(SETTING, enabled, true);
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getProject() != project || !isSourceEditor(editor)) continue;
            if (enabled) attach(editor);
            else detach(editor);
        }
    }

    void attach(Editor editor) {
        if (!enabled(project) || editor.isDisposed() || sessions.containsKey(editor)) return;
        EditorComments session = new EditorComments(project, editor);
        Disposer.register(this, session);
        sessions.put(editor, session);
        session.start();
    }

    void detach(Editor editor) {
        EditorComments session = sessions.remove(editor);
        if (session != null) Disposer.dispose(session);
    }

    @Override
    public void dispose() {
        sessions.clear();
    }
}
