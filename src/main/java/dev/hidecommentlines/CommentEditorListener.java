package dev.hidecommentlines;

import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;

public final class CommentEditorListener implements EditorFactoryListener, DynamicPluginListener {
    @Override
    public void pluginLoaded(IdeaPluginDescriptor descriptor) {
        if ("dev.hidecommentlines".equals(descriptor.getPluginId().getIdString())) {
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) attach(editor);
        }
    }

    @Override
    public void editorCreated(EditorFactoryEvent event) {
        attach(event.getEditor());
    }

    private static void attach(Editor editor) {
        Project project = editor.getProject();
        if (project != null && !project.isDisposed() && CommentView.isSourceEditor(editor)) {
            project.getService(CommentView.class).attach(editor);
        }
    }

    @Override
    public void editorReleased(EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Project project = editor.getProject();
        if (project != null && !project.isDisposed()) {
            CommentView view = project.getServiceIfCreated(CommentView.class);
            if (view != null) view.detach(editor);
        }
    }
}
