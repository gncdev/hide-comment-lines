package dev.hidecommentlines;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;

public final class ToggleComments extends ToggleAction implements DumbAware {
    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public boolean isSelected(AnActionEvent event) {
        Project project = event.getProject();
        return project != null && CommentView.enabled(project);
    }

    @Override
    public void setSelected(AnActionEvent event, boolean state) {
        Project project = event.getProject();
        if (project != null) project.getService(CommentView.class).setEnabled(state);
    }

    @Override
    public void update(AnActionEvent event) {
        super.update(event);
        event.getPresentation().setEnabled(event.getProject() != null);
    }
}
