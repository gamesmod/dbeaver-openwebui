package io.dbtools.openwebui.context;

import io.dbtools.openwebui.prefs.Prefs;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextUtilities;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

/**
 * Вставка SQL в документ SQL-редактора. Вызывать только из UI-потока.
 */
public final class EditorInserter {

    private EditorInserter() {
    }

    /**
     * Вставляет SQL согласно настройке «Вставка сгенерированного SQL».
     *
     * @return true, если вставка выполнена
     */
    public static boolean insert(SqlEditorContext ctx, String sql) {
        SQLEditor editor = ctx.editor();
        if (editor == null || editor.getTextViewer() == null || editor.getTextViewer().getTextWidget() == null
            || editor.getTextViewer().getTextWidget().isDisposed()) {
            return false;
        }
        IDocument doc = editor.getDocument();
        if (doc == null) {
            return false;
        }
        String mode = Prefs.insertMode();
        String nl = TextUtilities.getDefaultLineDelimiter(doc);
        try {
            int offset;
            int length = 0;
            String text;
            if (Prefs.INSERT_REPLACE_SELECTION.equals(mode) && ctx.isFromSelection()) {
                offset = ctx.queryOffset();
                length = ctx.queryLength();
                text = sql;
            } else if (Prefs.INSERT_NEW_LINE_BELOW.equals(mode) && ctx.queryText() != null) {
                offset = Math.min(doc.getLength(), ctx.queryOffset() + ctx.queryLength());
                // Переходим в конец строки, чтобы не разрезать хвост (например, «;» или комментарий).
                int line = doc.getLineOfOffset(offset);
                offset = doc.getLineOffset(line) + doc.getLineLength(line);
                String delimiter = doc.getLineDelimiter(line);
                String prefix = delimiter == null ? nl + nl : nl;
                text = prefix + terminate(sql) + nl;
            } else {
                offset = Math.min(doc.getLength(), ctx.caretOffset());
                text = terminate(sql);
            }
            doc.replace(offset, length, text);
            int start = offset + (text.length() - text.stripLeading().length());
            editor.selectAndReveal(start, text.strip().length());
            editor.setFocus();
            return true;
        } catch (BadLocationException e) {
            // Документ изменился, пока шёл запрос — вставляем в конец.
            try {
                String tail = nl + nl + terminate(sql);
                doc.replace(doc.getLength(), 0, tail);
                return true;
            } catch (BadLocationException ignored) {
                return false;
            }
        }
    }

    /** Точка с запятой в конце, чтобы DBeaver корректно разделил запросы в скрипте. */
    static String terminate(String sql) {
        String s = sql.strip();
        return s.endsWith(";") || s.endsWith("/") ? s : s + ";";
    }
}
