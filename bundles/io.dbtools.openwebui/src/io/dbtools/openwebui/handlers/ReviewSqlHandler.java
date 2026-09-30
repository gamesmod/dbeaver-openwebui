package io.dbtools.openwebui.handlers;

import io.dbtools.openwebui.ai.PromptFactory;

/** Команда «Найти ошибки в запросе». */
public class ReviewSqlHandler extends QueryActionHandler {

    public ReviewSqlHandler() {
        super(PromptFactory.QueryAction.REVIEW);
    }
}
