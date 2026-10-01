# Open WebUI для AI-ассистента DBeaver

[![build](../../actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)

Плагин добавляет во встроенный AI-ассистент DBeaver Community Edition новый движок
**«Open WebUI (OpenAI-compatible)»**. Движок появляется в *Окно → Параметры → AI → Движки* рядом с OpenAI
и GitHub Copilot. Через него работают штатные функции ассистента: AI-чат, генерация SQL, команда `@ai`
в редакторе и вызов функций для чтения метаданных БД.

![AI-чат DBeaver через Open WebUI](docs/screenshots/chat.png)

## Изменения

- **2.0.2** — документация со скриншотами и раздел «Использование»; скриншоты снимаются автоматически
  в CI на DBeaver CE 26.2.1. Код плагина не менялся.
- **2.0.1** — релиз публикуется сборкой сразу с архивами update site (в 2.0.0 их нет).
- **2.0.0** — новый плагин `dbeaver.openwebui.ai`: движок для встроенного AI-ассистента DBeaver 26.2+
  вместо собственных панелей и команд. Плагин 1.x (`io.dbtools.openwebui`) удалён из репозитория;
  его последняя версия осталась в [Releases → v1.0.2](../../releases/tag/v1.0.2).

## Зачем отдельный движок

Встроенный движок OpenAI в DBeaver 26.x обращается к **Responses API** (`POST <base>/responses`).
Open WebUI отдаёт только **Chat Completions API**:

| Что | Open WebUI | Встроенный OpenAI-движок DBeaver |
|---|---|---|
| Список моделей | `GET /api/models` | `GET <base>/models` |
| Чат | `POST /api/chat/completions` | `POST <base>/responses` |

Поэтому подставить URL Open WebUI во встроенный движок не получится. Плагин реализует отдельный
движок поверх Chat Completions. Он подходит и для других совместимых серверов: LiteLLM, vLLM,
LM Studio, Ollama (`/v1`), корпоративных шлюзов.

## Возможности

- Загрузка списка моделей Open WebUI (модели Ollama, OpenAI-подключения, пайплайны, кастомные модели).
- Размер контекста берётся из карточки модели (`num_ctx`, `context_length`), иначе 32 768 (можно изменить).
- Потоковые ответы (SSE) с подсчётом токенов (`stream_options.include_usage`).
- Вызов функций (tools): ассистент сам читает список схем, таблиц и DDL. Учтены особенности Ollama:
  `finish_reason: "stop"` вместо `tool_calls`, аргументы объектом, отсутствие `index`.
- Скрытие рассуждений `<think>…</think>` (DeepSeek-R1, Qwen3, QwQ), в том числе когда тег разорван между чанками.
- Повтор запроса без `temperature` для моделей, которые его не принимают (o-серия, gpt-5 через OpenAI-подключение).
- API-ключ хранится в защищённом хранилище DBeaver, а не в JSON профиля.
- Дополнительные HTTP-заголовки (Cloudflare Access, корпоративный прокси).
- Понятные ошибки: `{"detail": ...}` от Open WebUI, 401/404, HTML-страница вместо JSON при неверном URL.
- Интерфейс на русском и английском.

## Требования

- **DBeaver CE 26.2 или новее.** Плагин использует AI API DBeaver (`org.jkiss.dbeaver.model.ai`,
  `org.jkiss.dbeaver.ui.ai`), он быстро меняется между версиями. Версия, на которой собран и проверен
  конкретный релиз, указана в его описании.
- Open WebUI с включёнными API-ключами или любой сервер OpenAI Chat Completions.

## Установка

DBeaver **не подхватывает плагины из папки `dropins`** (реконсилер p2 там не запускается),
поэтому ставьте через update site:

1. Скачайте `dbeaver-openwebui-ai-site-<версия>.zip` со страницы
   [Releases](../../releases/latest) (или соберите сами, см. ниже).
2. DBeaver → **Help → Install New Software… → Add… → Archive…** → выберите zip.
3. Отметьте *Open WebUI integration*, **Next → Finish**, подтвердите установку неподписанного
   содержимого, перезапустите DBeaver.

Для массовой установки без GUI (например, из скрипта развёртывания):

```bash
cd "$DBEAVER_HOME"
jre/bin/java -jar plugins/org.eclipse.equinox.launcher_*.jar -nosplash \
  -application org.eclipse.equinox.p2.director \
  -repository "jar:file:/path/dbeaver-openwebui-ai-site-<версия>.zip!/" \
  -installIU dbeaver.openwebui.ai.feature.feature.group
```

Обновление с предыдущей версии в консоли — в одной команде снять старую и поставить новую
(через Help → Install New Software DBeaver делает это сам):

```bash
... -uninstallIU dbeaver.openwebui.ai.feature.feature.group -installIU dbeaver.openwebui.ai.feature.feature.group
```

**Если стоял плагин 1.x** (`io.dbtools.openwebui`): удалите его через *Help → About DBeaver →
Installation Details → Installed Software → Open WebUI for DBeaver → Uninstall*. Оба плагина могут стоять
одновременно, но это разные продукты. В консоли: `-uninstallIU io.dbtools.openwebui.feature.feature.group`.

**Удаление:** *Installation Details → Installed Software → Open WebUI engine for DBeaver AI assistant → Uninstall*.

## Настройка

1. В Open WebUI включите API-ключи: *Admin Panel → Settings → General → Enable API Keys*.
   Затем создайте ключ: *Settings → Account → API Keys*.
2. В DBeaver откройте настройки AI: шестерёнка в панели *AI Chat* (или *Окно → Параметры → AI*) →
   *Model configurations* → **+** (*Create new profile*) → движок **Open WebUI (OpenAI-compatible)**.

   ![Выбор движка при создании профиля](docs/screenshots/new-profile.png)

3. Заполните:
   - **Базовый URL** — `http://host:3000/api`. Если указать только `http://host:3000`, `/api` добавится сам.
     Для vLLM/LiteLLM/LM Studio — `http://host:port/v1`.
   - **API-ключ** — `sk-…` из Open WebUI (или JWT). Оставьте пустым, если аутентификация отключена.
   - **Модель** — кнопка обновления загрузит список с сервера.
   - **Размер контекста** — подставляется из модели. Для Ollama проверьте значение: по умолчанию там часто 2048–8192.
4. *Test Connection* → OK, затем *Apply and Close*.

   ![Настройки профиля Open WebUI](docs/screenshots/settings.png)

   ![Проверка подключения](docs/screenshots/test-connection.png)

## Использование

Откройте панель *AI Chat* (*Window → AI Chat*), внизу выберите профиль **Open WebUI** и модель.
Список моделей загружается с сервера, *Refresh models* обновляет его.

<p>
  <img src="docs/screenshots/chat-panel.png" alt="Ответ модели в AI-чате" width="325">
  <img src="docs/screenshots/models.png" alt="Выбор модели Open WebUI" width="325">
</p>

Ответы приходят потоком. SQL из ответа можно выполнить, вставить в редактор или скопировать кнопками
под блоком кода. Если в чате выбрано подключение к БД, модель сама запрашивает список схем, таблиц и DDL
через функции DBeaver (нужна модель с поддержкой tool calling).

Скриншоты сняты автоматически workflow *docs* на DBeaver CE 26.2.1 с mock-сервером Open WebUI
(*Actions → docs → Run workflow*, флажок *publish* обновляет картинки в `docs/screenshots`).

Для SQL лучше подходят модели с нативным tool calling (Qwen2.5/3, Llama 3.1+, GPT-4o и т.п.). Если модель
не умеет tools, снимите «Разрешить вызов функций»: ассистент будет работать по контексту, без чтения метаданных.

## Устранение проблем

| Симптом | Причина / решение |
|---|---|
| Движка нет в списке после установки | Версия DBeaver ниже 26.2, или DBeaver не перезапущен |
| `Server returned an HTML page instead of JSON` | URL без `/api`, либо указан адрес веб-интерфейса за другим путём |
| `HTTP 401: Not authenticated` | Неверный ключ или API-ключи не включены администратором |
| `HTTP 404/405` | Неверный путь. Для Open WebUI — `/api`, для vLLM — `/v1` |
| Ответ приходит целиком в конце | Прокси буферизует SSE: отключите буферизацию (`proxy_buffering off` в nginx) или снимите «Потоковые ответы» |
| Таймаут на первом запросе к Ollama | Модель загружается в память: увеличьте таймаут в «Дополнительно» |
| Ассистент не видит таблицы | Модель не поддерживает tools или снят флажок вызова функций |
| Отладка | «Дополнительно» → логирование запросов, лог: `~/.local/share/DBeaverData/workspace6/.metadata/dbeaver-debug.log` (API-ключ в лог не пишется) |

## Сборка

Нужны JDK 21+ и установленный DBeaver CE 26.2+: он служит целевой платформой и p2-публикатором.
Maven и доступ в интернет не нужны.

```bash
DBEAVER_HOME=/opt/dbeaver ./scripts/build-offline.sh
# → dist/dbeaver.openwebui.ai_<версия>.jar и dist/dbeaver-openwebui-ai-site-<версия>.zip
```

На macOS `DBEAVER_HOME=/Applications/DBeaver.app/Contents/Eclipse`, на Windows — Git Bash или WSL
с `DBEAVER_HOME="/c/Program Files/DBeaver"`.

### Тесты

```bash
DBEAVER_HOME=/opt/dbeaver ./scripts/run-tests.sh       # или GSON_JAR=/path/gson.jar
```

45 проверок против mock-сервера Open WebUI, DBeaver запускать не нужно. Код плагина компилируется
против заглушек API (`tests/api-stubs`, сигнатуры из исходников DBeaver 26.2). Проверяются:
нормализация URL, модели и `num_ctx`, 401 и HTML вместо JSON, поток с разорванным `<think>`, usage,
два tool call с аргументами по кускам и `finish_reason: stop`, аргументы объектом, синхронный режим,
эмуляция потока, повтор без temperature, ошибка `detail`, конвертация истории (в том числе вызовы
от другого движка без id), заголовки, отсутствие секретов в JSON профиля.

### CI

GitHub Actions на каждый push и pull request:

1. скачивает последний релиз DBeaver CE (версию можно задать вручную при запуске *Run workflow*);
2. проверяет, что в нём есть нужные точки расширения AI;
3. прогоняет тесты;
4. собирает плагин против настоящих jar DBeaver — это и есть проверка совместимости с его API;
5. ставит update site через p2 director в копию DBeaver.

Выпуск версии: поднимите `Bundle-Version` (бандл и feature), затем *Actions → build → Run workflow*
и в поле *release_tag* укажите `v<версия>`. Workflow соберёт архивы, создаст тег и опубликует релиз
сразу с файлами. Можно и запушить тег `v<версия>`, результат тот же.

> В репозитории включены неизменяемые релизы: к опубликованному релизу файлы добавить нельзя.
> Поэтому не создавайте релиз вручную через *Draft a new release → Publish* — он выйдет без архивов.

## Структура

```
bundles/dbeaver.openwebui.ai/
  plugin.xml                     регистрация движка и панели настроек
  META-INF/MANIFEST.MF           OSGi-бандл
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
features/dbeaver.openwebui.ai.feature/   feature для update site
releng/site/category.xml                 категория update site
scripts/build-offline.sh                 сборка плагина и update site
scripts/run-tests.sh                     тесты
scripts/docs-screenshots.sh, docs/demo/  скриншоты для README (workflow docs)
tests/                                   заглушки API DBeaver, mock Open WebUI, тесты
```

### Точки интеграции с DBeaver

- расширение `com.dbeaver.ai.engine` → `completionEngine` (класс движка и класс свойств);
- расширение `org.jkiss.dbeaver.ui.propertyConfigurator` → панель настроек (поиск по точному имени класса движка);
- `BaseCompletionEngine`, `AbstractHttpAIClient` (HTTP/1.1 — важно для uvicorn, на котором работает Open WebUI),
  `AIEngineResponseConsumer`, `AbstractAIEngineConfigurator`, `ModelSelectorField`, `ContextWindowSizeField`.

## Ограничения

- С настоящим сервером Open WebUI плагин пока не проверялся: в CI вместо него mock-сервер.
  Запуск в DBeaver 26.2.1, настройки, загрузка моделей, проверка подключения и потоковый ответ в чате
  проверены в workflow *docs*.
- Update site не подписан: при установке DBeaver попросит подтвердить установку неподписанного содержимого.

## Лицензия

MIT, см. [LICENSE](LICENSE). Файлы в `tests/api-stubs`, скопированные из исходников DBeaver,
распространяются под Apache License 2.0 (заголовки сохранены).
