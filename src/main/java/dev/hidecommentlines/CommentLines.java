package dev.hidecommentlines;

import com.intellij.lang.LanguageParserDefinitions;
import com.intellij.lang.ParserDefinition;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.tree.IElementType;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

final class CommentLines {
    record Run(int first, int last) {}

    static List<Run> find(Document document, HighlighterIterator tokens) {
        CharSequence text = document.getImmutableCharSequence();
        BitSet comments = new BitSet();
        BitSet code = new BitSet();
        int offset = 0;
        int line = 0;
        while (offset < text.length()) {
            ProgressManager.checkCanceled();
            while (!tokens.atEnd() && tokens.getEnd() <= offset) tokens.advance();
            boolean inToken = !tokens.atEnd() && tokens.getStart() <= offset;
            int end = tokens.atEnd() ? text.length()
                    : Math.min(text.length(), inToken ? tokens.getEnd() : tokens.getStart());
            boolean comment = inToken && isComment(tokens);
            while (offset < end) {
                char c = text.charAt(offset++);
                if (comment) comments.set(line);
                else if (!Character.isWhitespace(c)) code.set(line);
                if (c == '\n') line++;
            }
        }
        comments.andNot(code);
        List<Run> runs = new ArrayList<>();
        for (int first = comments.nextSetBit(0); first >= 0; ) {
            int after = comments.nextClearBit(first);
            runs.add(new Run(first, after - 1));
            first = comments.nextSetBit(after);
        }
        return runs;
    }

    private static boolean isComment(HighlighterIterator token) {
        IElementType type = token.getTokenType();
        if (type != null) {
            ParserDefinition parser = LanguageParserDefinitions.INSTANCE.forLanguage(type.getLanguage());
            if (parser != null && parser.getCommentTokens().contains(type)) return true;
        }
        for (TextAttributesKey key : token.getTextAttributesKeys()) {
            for (; key != null; key = key.getFallbackAttributeKey()) {
                if (key == DefaultLanguageHighlighterColors.LINE_COMMENT
                        || key == DefaultLanguageHighlighterColors.BLOCK_COMMENT
                        || key == DefaultLanguageHighlighterColors.DOC_COMMENT
                        || key == DefaultLanguageHighlighterColors.DOC_COMMENT_TAG
                        || key == DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE
                        || key == DefaultLanguageHighlighterColors.DOC_COMMENT_MARKUP) return true;
            }
        }
        return false;
    }
}
