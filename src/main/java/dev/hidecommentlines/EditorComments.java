package dev.hidecommentlines;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.editor.ex.FoldingModelEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.*;

final class EditorComments implements Disposable {
    private final Project project;
    private final Editor editor;
    private final Document document;
    private final Alarm refresh = new Alarm(this);
    private final List<FoldRegion> owned = new ArrayList<>();
    private List<CommentLines.Run> runs = List.of();
    private long scannedStamp = -1;
    private boolean waitingForCaret;

    EditorComments(Project project, Editor editor) {
        this.project = project;
        this.editor = editor;
        this.document = editor.getDocument();
    }

    void start() {
        document.addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(DocumentEvent event) {
                schedule();
            }
        }, this);
        editor.getCaretModel().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(CaretEvent event) {
                if (waitingForCaret && scannedStamp == document.getModificationStamp()
                        && event.getOldPosition().line != event.getNewPosition().line) {
                    refresh.cancelAllRequests();
                    refresh.addRequest(EditorComments.this::apply, 100);
                }
            }
        }, this);
        schedule();
    }

    private void schedule() {
        refresh.cancelAllRequests();
        refresh.addRequest(this::scan, 160);
    }

    private void scan() {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !file.isValid() || editor.isDisposed()) return;
        long stamp = document.getModificationStamp();
        ReadAction.nonBlocking(() -> {
            EditorHighlighter highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file);
            highlighter.setText(document.getImmutableCharSequence());
            return CommentLines.find(document, highlighter.createIterator(0));
        }).expireWith(this).expireWhen(() -> document.getModificationStamp() != stamp || !file.isValid())
                .coalesceBy(this).finishOnUiThread(ModalityState.nonModal(), result -> {
                    if (editor.isDisposed() || document.getModificationStamp() != stamp) return;
                    runs = result;
                    scannedStamp = stamp;
                    apply();
                }).submit(AppExecutorUtil.getAppExecutorService());
    }

    private void apply() {
        if (editor.isDisposed() || document.getModificationStamp() != scannedStamp) return;
        if (!(editor.getFoldingModel() instanceof FoldingModelEx model)) return;
        Map<TextRange, FoldRegion> existing = new HashMap<>();
        for (FoldRegion region : owned) {
            if (region.isValid()) existing.put(new TextRange(region.getStartOffset(), region.getEndOffset()), region);
        }
        List<CommentLines.Run> missing = new ArrayList<>();
        List<FoldRegion> next = new ArrayList<>();
        for (CommentLines.Run run : runs) {
            FoldRegion match = existing.remove(range(run, true));
            if (match == null) match = existing.remove(range(run, false));
            if (match == null) missing.add(run);
            else next.add(match);
        }
        waitingForCaret = false;
        model.runBatchFoldingOperationDoNotCollapseCaret(() -> {
            for (FoldRegion obsolete : existing.values()) model.removeFoldRegion(obsolete);
            for (CommentLines.Run run : missing) {
                TextRange preferred = range(run, true);
                FoldRegion added = hide(model, preferred);
                if (added == null) {
                    TextRange alternate = range(run, false);
                    if (!preferred.equals(alternate)) {
                        added = hide(model, alternate);
                    }
                }
                if (added != null) next.add(added);
            }
        });
        owned.clear();
        owned.addAll(next);
    }

    private FoldRegion hide(FoldingModelEx model, TextRange range) {
        FoldRegion region = model.createFoldRegion(range.getStartOffset(), range.getEndOffset(), "", null, true);
        if (region == null) {
            for (Caret caret : editor.getCaretModel().getAllCarets()) {
                int offset = caret.getOffset();
                if (range.getStartOffset() < offset && offset < range.getEndOffset()) waitingForCaret = true;
            }
        }
        return region;
    }

    private TextRange range(CommentLines.Run run, boolean preferPrecedingBreak) {
        int start = document.getLineStartOffset(run.first());
        int end = document.getLineEndOffset(run.last());
        boolean hasFollowingBreak = run.last() + 1 < document.getLineCount();
        if (start > 0 && (preferPrecedingBreak || !hasFollowingBreak)) start--;
        else if (hasFollowingBreak) end++;
        return new TextRange(start, end);
    }

    @Override
    public void dispose() {
        if (!editor.isDisposed() && !owned.isEmpty()) {
            FoldingModel model = editor.getFoldingModel();
            model.runBatchFoldingOperation(() -> {
                for (FoldRegion region : owned) if (region.isValid()) model.removeFoldRegion(region);
            });
        }
        owned.clear();
    }
}
