package io.dbtools.openwebui.api;

/**
 * Получатель потокового ответа модели. Вызывается из фонового потока.
 */
@FunctionalInterface
public interface StreamListener {

    /** Очередной фрагмент текста ответа. */
    void onDelta(String text);

    /** Возвращает true, если пользователь отменил запрос и чтение потока нужно прекратить. */
    default boolean isCancelled() {
        return false;
    }
}
