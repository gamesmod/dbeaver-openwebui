package io.dbtools.openwebui.handlers;

import io.dbtools.openwebui.ai.PromptFactory;

/** Команда «Оптимизировать запрос». */
public class OptimizeSqlHandler extends QueryActionHandler {

    public OptimizeSqlHandler() {
        super(PromptFactory.QueryAction.OPTIMIZE);
    }
}
