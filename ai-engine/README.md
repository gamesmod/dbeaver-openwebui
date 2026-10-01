# Open WebUI engine для AI-ассистента DBeaver CE

Плагин добавляет в DBeaver Community Edition новый AI-движок **«Open WebUI (OpenAI-compatible)»**.
Он появляется в *Окно → Параметры → AI → Движки* рядом со встроенными OpenAI и GitHub Copilot
и работает со всеми функциями ассистента: AI-чат, генерация SQL, команда `@ai` в редакторе, вызов функций
для чтения метаданных БД.

## Зачем отдельный движок

Встроенный движок OpenAI в DBeaver 26.x ходит в **Responses API** (`POST /v1/responses`).
Open WebUI отдаёт только **Chat Completions API**:

| Что | Open WebUI | Встроенный OpenAI-движок DBeaver |
|---|---|---|
| Список моделей | `GET /api/models` | `GET <base>/models` |
| Чат | `POST /api/chat/completions` | `POST <base>/responses` |

Поэтому просто подставить URL Open WebUI во встроенный движок не получается. Плагин реализует
отдельный движок поверх Chat Completions. Он подходит и для других совместимых серверов:
LiteLLM, vLLM, LM Studio, Ollama (`/v1`), корпоративные шлюзы.

## Возможности

- Загрузка списка моделей Open WebUI (включая модели Ollama, OpenAI-подключения, пайплайны, кастомные модели).
- Размер контекста подтягивается из карточки модели (`num_ctx`, `context_length`), иначе 32 768 (можно поменять).
- Потоковые ответы (SSE) с учётом токенов (`stream_options.include_usage`).
- Вызов функций (tools) — ассистент сам читает список схем, таблиц и DDL. Поддерживаются варианты
  Ollama: `finish_reason: "stop"` вместо `tool_calls`, аргументы объектом, отсутствие `index`.
- Скрытие рассуждений `<think>…</think>` (DeepSeek-R1, Qwen3, QwQ), корректно при разрыве тега между чанками.
- Автоповтор без `temperature` для моделей, которые его не принимают (o-серия, gpt-5 через OpenAI-подключение).
- API-ключ хранится в защищённом хранилище DBeaver, не в JSON профиля.
- Дополнительные HTTP-заголовки (Cloudflare Access, корпоративный прокси).
- Понятные ошибки: `{"detail": ...}` FastAPI/Open WebUI, 401/404, HTML-страница вместо JSON при неверном URL.
- Интерфейс на русском и английском.

## Совместимость

Собрано против исходников DBeaver **26.2.x** (бандлы `org.jkiss.dbeaver.model.ai` 2.0.x и
`org.jkiss.dbeaver.ui.ai` 1.0.x). AI API DBeaver быстро меняется. На других версиях может
понадобиться адаптация, см. «Точки интеграции». Нужна Java 21 (JDK для сборки).

## Сборка и установка

Нужен JDK 21+ (`javac`, `jar`). Встроенный JRE DBeaver для сборки не подходит.

**Linux / macOS**

```bash
cd ai-engine
export DBEAVER_HOME=/usr/share/dbeaver-ce          # или /Applications/DBeaver.app/Contents/Eclipse
./build.sh --install-bundles                       # сборка + регистрация бандла
dbeaver -clean                                     # первый запуск с -clean
```

**Windows (PowerShell от администратора)**

```powershell
cd ai-engine
.\build.ps1 -DBeaverHome "C:\Program Files\DBeaver" -InstallBundles
& "C:\Program Files\DBeaver\dbeaver.exe" -clean
```

Скрипт кладёт jar в `plugins/` и добавляет строку в
`configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` (резервная копия — `bundles.info.bak`).
Вариант `--install` / `-Install` копирует jar в `dropins/`, но DBeaver 25.x и новее эту папку обычно
не подхватывает, поэтому основной способ — регистрация бандла.

**Удаление:** удалить jar из `plugins/` и строку `dbeaver.openwebui.ai,…` из `bundles.info`
(или вернуть `bundles.info.bak`), запустить с `-clean`.

> Корневой update site репозитория (`io.dbtools.openwebui`) этот движок пока не включает.
> Включение в feature и p2-сайт — отдельная задача (см. «Оценка трудозатрат»).

## Настройка

1. В Open WebUI включите API-ключи: *Admin Panel → Settings → General → Enable API Keys*.
   Затем создайте ключ: *Settings → Account → API Keys*.
2. В DBeaver: *Окно → Параметры → AI → Движки* → добавить профиль → движок **Open WebUI (OpenAI-compatible)**.
3. Заполните:
   - **Базовый URL** — `http://host:3000/api`. Если указать только `http://host:3000`, `/api` добавится сам.
     Для vLLM/LiteLLM/LM Studio — `http://host:port/v1`.
   - **API-ключ** — `sk-…` из Open WebUI (или JWT). Пусто, если аутентификация отключена.
   - **Модель** — кнопка обновления загрузит список с сервера.
   - **Размер контекста** — подставляется из модели, проверьте для Ollama (часто 2048–8192 по умолчанию).
4. «Проверить подключение» → OK.

Для SQL лучше модели с нативным tool calling (Qwen2.5/3, Llama 3.1+, GPT-4o и т.п.). Если модель
не умеет tools, снимите «Разрешить вызов функций»: ассистент будет работать по контексту без чтения метаданных.

## Устранение проблем

| Симптом | Причина / решение |
|---|---|
| `Server returned an HTML page instead of JSON` | URL без `/api`, либо указан адрес веб-интерфейса за другим путём |
| `HTTP 401: Not authenticated` | Неверный ключ или API-ключи не включены админом |
| `HTTP 404/405` | Неверный путь. Для Open WebUI — `/api`, для vLLM — `/v1` |
| Ответ приходит целиком в конце | Прокси буферизует SSE: отключите буферизацию (`proxy_buffering off` в nginx) или снимите «Потоковые ответы» |
| Таймаут на первом запросе к Ollama | Модель загружается в память: увеличьте таймаут в «Дополнительно» |
| Ассистент не видит таблицы | Модель не поддерживает tools или галка функций снята |
| Отладка | «Дополнительно» → логирование запросов; смотрите `~/.local/share/DBeaverData/workspace6/.metadata/dbeaver-debug.log` (API-ключ в лог не пишется) |

## Структура

```
plugin.xml                     регистрация движка и панели настроек
META-INF/MANIFEST.MF           OSGi-бандл dbeaver.openwebui.ai
src/dbeaver/openwebui/model/
  OpenWebUIEngine.java         AIEngine: модели, запрос, поток, повтор без temperature
  OpenWebUIClient.java         HTTP: /models, /chat/completions, заголовки, ошибки, нормализация URL
  OpenWebUIProperties.java     настройки профиля; ключ — в secure storage
  ChatStreamHandler.java       разбор SSE, сборка tool calls из дельт
  ChatMessageConverter.java    история DBeaver → messages/tools Chat Completions
  ThinkTagFilter.java          потоковое удаление <think>…</think>
  ChatDto.java, JsonSupport.java
src/dbeaver/openwebui/ui/
  OpenWebUIConfigurator.java   SWT-панель настроек (+ сообщения ru/en)
tests/                         офлайн-тесты: заглушки API DBeaver, mock Open WebUI, 45 проверок
```

### Точки интеграции с DBeaver

- расширение `com.dbeaver.ai.engine` → `completionEngine` (класс движка + класс свойств);
- расширение `org.jkiss.dbeaver.ui.propertyConfigurator` → панель настроек; поиск идёт по точному имени класса движка;
- `BaseCompletionEngine`, `AbstractHttpAIClient` (HTTP/1.1 — важно для uvicorn, на котором работает Open WebUI),
  `AIEngineResponseConsumer`, `AbstractAIEngineConfigurator`, `ModelSelectorField`, `ContextWindowSizeField`.

## Тесты

```bash
GSON_JAR=$DBEAVER_HOME/plugins/com.google.gson_*.jar ./tests/run-tests.sh
```

Проверяются: нормализация URL, модели и `num_ctx`, 401 и HTML вместо JSON, поток с разорванным `<think>`,
usage, два параллельных tool call с аргументами по кускам и `finish_reason: stop`, аргументы объектом,
синхронный режим, эмуляция потока, повтор без temperature, ошибка `detail`, конвертация истории
(в т.ч. вызовы от другого движка без id), заголовки, секреты не попадают в JSON профиля.

Что **не** проверено: запуск внутри реального DBeaver (UI-код скомпилирован против заглушек SWT/DBeaver с
реальными сигнатурами из исходников 26.2) и работа с живым Open WebUI.

## Оценка трудозатрат

| Этап | Чел.-дни | Статус |
|---|---|---|
| Анализ AI API DBeaver 26.2, выбор точек расширения | 1,0 | сделано |
| Модель: клиент Chat Completions, SSE, tool calls, `<think>`, ошибки, повторы | 2,0 | сделано |
| Панель настроек SWT, локализация ru/en, сборочные скрипты | 1,0 | сделано |
| Офлайн-тесты (заглушки API, mock-сервер) | 1,0 | сделано |
| Проверка в реальном DBeaver 26.2 + Open WebUI (Ollama и OpenAI-бэкенды), исправления | 1,5–2,0 | осталось |
| Сборка Tycho и p2 update site для установки через *Help → Install New Software* | 1,0 | опционально |
| Проверка на Windows/macOS, документация для пользователей | 0,5 | осталось |
| **Итого с нуля** | **8,0–8,5** | |
| **Осталось до продуктивного использования** | **2,0–2,5** (+1,0 на update site) | |

## Лицензия

Apache License 2.0 (как и DBeaver CE).
