package io.dbtools.openwebui.api;

/**
 * Модель, доступная в Open WebUI (ответ GET /api/models).
 *
 * @param id   идентификатор, который передаётся в поле "model"
 * @param name человекочитаемое имя (может совпадать с id)
 */
public record ModelInfo(String id, String name) {

    @Override
    public String toString() {
        return name == null || name.isBlank() || name.equals(id) ? id : name + " (" + id + ")";
    }
}
