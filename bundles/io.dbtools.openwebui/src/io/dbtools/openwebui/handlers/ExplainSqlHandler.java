package io.dbtools.openwebui.handlers;

import io.dbtools.openwebui.ai.PromptFactory;

/** Команда «Объяснить запрос». */
public class ExplainSqlHandler extends QueryActionHandler {

    public ExplainSqlHandler() {
        super(PromptFactory.QueryAction.EXPLAIN);
    }
}
