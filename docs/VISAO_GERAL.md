# ObsidianTasks Pebble Bridge — Visão Geral

> **Propósito deste ficheiro:** contexto completo do projeto para humanos e para
> sessões futuras do Claude (lê isto primeiro em vez de re-explorar o código).
> Atualizado: 2026-07-08.

## O que é

Sistema de 3 componentes que mostra as tarefas do Obsidian num relógio Pebble
(Pebble Time 2 / Core Devices) e permite concluí-las/adiá-las do pulso, além de
ditar notas novas por voz.

```
Obsidian (vault .md no telemóvel)
        │  (ficheiros lidos via SAF)
        ▼
App Android "ObsidianTasksPebbleBridge"  ←— broadcast SYNC_NOW (Tasker)
        │  AppMessage via app Core Devices
        ▼
Watchapp Pebble "ObsidianTasks" (C) + PKJS (timeline pins via Rebble API)
```

## Localizações no disco

| O quê | Onde | Notas |
|---|---|---|
| Repo git (canónico, mirror GitHub `bquelhas/ObsidianTasksPebbleBridge`) | `/home/bquelhas/ObsidianTasksPebbleBridge` | Android + cópia do watchapp em `watchapp/` |
| **Working copy do watchapp** (onde se desenvolve/compila) | `/home/bquelhas/projetos/teste_obsidian` | **NÃO é git.** Ao terminar trabalho, copiar `src/` para o mirror e commitar lá |
| Releases | `release-v2.2/` no repo (APK + .pbw + `publish.sh`/`update-pbw.sh`) | Publicação na Rebble store é manual (o Bruno faz upload) |
| Screenshots com dados mock | `projetos/teste_obsidian/screenshots_mock/` | README lá explica como regenerar (SEED_TEST) |

## Watchapp (C, `src/c/teste_obsidian.c`, ~1850 linhas, ficheiro único)

- **Plataformas:** aplite, basalt, chalk, diorite, emery (o relógio real é emery = Pebble Time 2, 200×228 cor). aplite tem ~1.5KB RAM livre — cuidado com memória; tudo é array estático, zero malloc.
- **Modelo de dados:** `s_items[30]` de `{text[100], tag[10], due[24]}`. `tag` é `"HEADER"` (título de grupo, desenhado em LECO maiúsculas com divisória) ou código de urgência: `W` atrasada ⚠ / `A` ≤7 dias ⏰ / `C` >7 dias 📅 / `N` sem data 📄. Ícones desenhados por gpath em `draw_urgency_icon`.
- **Fonte LECO:** usa a fonte de SISTEMA `FONT_KEY_LECO_20_BOLD_NUMBERS`. **Nunca usar `fonts_load_custom_font` — crasha o firmware do Time 2.** No firmware novo tem alfabeto completo; nos emuladores (firmware clássico) só tem números → letras = caixas. Para screenshots fiéis nos emuladores há um TTF em `resources/fonts/LECO_2014_subset.ttf` ativável (ver README dos screenshots).
- **Linha "New note"** (só relógios com micro, gated por config `VOICE_ON`): ditado por voz → placeholder "pending" com estado ?/! → enviado ao Android. Altura 36px, label centrado com o mic (corrigido 2026-07-08).
- **Persist keys:** 100 count, 200+ dados (formato `tag\x1f due\x1f text`), 299/300+ done-list, 700 linha selecionada, 800 cor de fundo, 801 voice on, 802 timestamp sync.
- **Sticky headers: NÃO FUNCIONA.** Tentado e revertido (2026-07). O MenuLayer do Pebble não fixa section headers no topo; overlay manual com `scroll_layer_get_content_offset` funcionou no emulador mas não satisfez no hardware. Não voltar a tentar sem ideia nova.
- **Timeline pins:** o Android empacota pins (delimitadores `\x1f` campo, `\x1e` pin, `\x1d` lista) → relógio reenvia ao PKJS (ação `PINJS`) → PKJS faz PUT/DELETE na API Rebble (`timeline-api.rebble.io`), reconciliação via localStorage. A app Core interceta e sincroniza localmente. **Ações custom de pins não aparecem na app Core (limitação upstream, só "Remove").**

## Protocolo AppMessage (relógio ↔ Android)

- Buffers: inbox 4096 / outbox 2048 no relógio. ~30 tarefas × ~110 bytes pode EXCEDER o inbox — há handler `inbox_dropped` (título "Sync incompleto") desde 2026-07-08.
- **Keys:** 0..29(?) itens da lista (cstring `tag\x1f due\x1f texto`), `90 KEY_ACTION` (cstring), `91 KEY_INDEX`, `92 KEY_DELAY` (só saída, não está no package.json — intencional), `93 KEY_TASK_TEXT`, `1000 BG_COLOR`, `1001 VOICE_ON`, `1002 TL_TOKEN`.
- **Ações relógio→Android:** `DONE` (index+texto), `REMIND` (index+delay), `NOTE` (texto ditado), `FETCH` (pede lista). Android→relógio: lista de itens, `NOTE_ERR` (falha ao gravar nota), pins `PINJS`, token timeline.
- ⚠️ **Fragilidade conhecida (backlog):** as ações usam INDEX da lista que o relógio tem + fallback por texto — se o vault mudou entretanto pode marcar a tarefa errada. Fix planeado: id estável por tarefa. NÃO mexer sem cuidado, a app funciona.

## App Android (Kotlin, `app/src/main/java/.../obsidiantaskspebblebridge/`)

| Ficheiro | Papel |
|---|---|
| `BackgroundReceiver.kt` (~1000 l) | O coração: recebe mensagens do relógio (via receiver runtime de `com.getpebble.action.app.RECEIVE` registado no `PebbleBridgeService`), varre o vault (SAF/DocumentFile), ordena/agrupa tarefas, envia lista ao relógio, edita os .md (marcar done, due dates, append de voice notes em `Notas do Relogio.md`?), empacota timeline pins, agenda reminders |
| `MainActivity.kt` (~850 l) | UI com ViewPager de 3 páginas: setup (vault picker, permissões), sync (log + preview), tag rules |
| `PebbleBridgeService.kt` | Foreground service que mantém o receiver runtime vivo |
| `SyncWorker.kt` / `NoteResyncWorker.kt` | WorkManager: sync periódico (15 min) e retry de notas |
| `TagScanner.kt`, `TagRuleAdapter.kt` | Regras de tags (tag → grupo/prioridade) |
| `TaskDates.kt` (+ teste) | Parsing de datas 📅 do formato Tasks |
| `ReminderStore/Receiver`, `BootReceiver` | Alarmes de lembretes, re-armados no boot |
| `TaskCache.kt` | Cache de conteúdo de ficheiros |
| `UpdateChecker.kt` | Verifica releases GitHub, abre a página (não faz sideload) |

- **Concorrência:** `ObsidianApp.io` = executor de UMA thread que serializa todos os read-modify-write ao vault. Manter isto.
- **Trigger externo:** receiver exportado `com.example.obsidiantaskspebblebridge.SYNC_NOW` — era o que o **Tasker** usava depois de abrir o Obsidian (o Obsidian só sincroniza o vault quando a app abre).
- **Substituto do Tasker (2026-07-09):** toggle "Abrir o Obsidian automaticamente antes de cada sync" no card Tasker da página Sync. Pref `openObsidianBeforeSync`; o `SyncWorker` abre o Obsidian (`md.obsidian`, precisa da entrada em `<queries>`), espera 45s pelo Obsidian Sync, e só depois dispara o FETCH. Requer SYSTEM_ALERT_WINDOW (exceção BAL, padrão do Steer) — concedida via `adb shell appops set com.bquelhas.obsidiantasks SYSTEM_ALERT_WINDOW allow`. Sem permissão/Obsidian degrada silenciosamente para o comportamento antigo.
- **Limitação de bloqueio (observação do Bruno) + mitigação:** com o ecrã bloqueado o Android suprime o launch — o `SyncWorker` deteta (`PowerManager.isInteractive` + `KeyguardManager.isKeyguardLocked`), salta o launch E a espera de 45s, e sincroniza só do disco. Em compensação, o `PebbleBridgeService` regista um receiver de `ACTION_USER_PRESENT`: ao desbloquear, se o último launch (`lastObsidianOpenMs`) for mais velho que a frequência escolhida, enfileira um `SyncWorker` one-shot que abre o Obsidian nesse momento. Ou seja: telemóvel pousado bloqueado = relógio recebe o que está no disco; ao pegar no telemóvel = refresh fresco automático (throttled).
- **Frequência de abertura própria (2026-07-09):** dropdown "Com que frequência abrir o Obsidian" (pref `obsidianOpenIntervalHours`, default 6 = 4×/dia; opções 1/4/6/8/12/24h), desacoplada do intervalo de sync do relógio (que continua barato/invisível a cada 15 min). O throttle aplica-se ao worker periódico E ao receiver de desbloqueio. Depois dos 45s de espera, a app **volta ao ecrã inicial**. Skips por bloqueio/throttle só vão a `Log.d` (não ao log da UI, para não inundar).
- **Modo invisível via Shizuku (2026-07-10, opcional):** segundo switch no card ("Modo invisível (Shizuku)", pref `useShizuku`). Se ligado E Shizuku ativo+permitido, o `SyncWorker` abre o Obsidian com `am start` através do Shizuku (`ShizukuLauncher.kt`, reflection sobre `Shizuku.newProcess(String[],String[],String)` — assinatura confirmada no AAR 13.1.5) — **invisível e funciona com o ecrã bloqueado**, sem check de keyguard/overlay nem go-home. Se o launch Shizuku falhar, cai no método visível. Deps: `dev.rikka.shizuku:api/provider:13.1.5`; `ShizukuProvider` no manifest (`${applicationId}.shizuku`); permissão `moe.shizuku.manager.permission.API_V23` (runtime, pedida via `Shizuku.requestPermission` + listener no MainActivity). Provado em device: `am start -n md.obsidian/.MainActivity` resume o Obsidian atrás do keyguard; Shizuku instalado (`moe.shizuku.privileged.api`) e permissão granted. FALTA teste E2E final (telemóvel com PIN, não desbloqueável por adb): ligar os 2 switches, bloquear, esperar sync, confirmar refresh sem pop-up.
- **UI limpo de Tasker (2026-07-09):** o card passou a "Atualização automática do vault" — removidos o intro e os 5 passos do Tasker (strings `tasker_*` apagadas; `item_tasker_step.xml` ficou órfão mas inofensivo). O pedido da permissão overlay passou a um `MaterialAlertDialogBuilder` que explica porquê ("nada é desenhado por cima; é só o que autoriza abrir outra app em segundo plano"); "Agora não" desliga o switch. O receiver `SYNC_NOW` continua a existir (compat externa), só saiu do UI.

## Build / instalar / testar

```bash
# Watchapp (na working copy!)
cd /home/bquelhas/projetos/teste_obsidian
pebble build                                   # SDK Core Devices 4.9.169 via uv tool
pebble install --phone 192.168.1.66            # Time 2 real (IP do telemóvel)
pebble screenshot --phone 192.168.1.66 --no-open
# Emuladores: basalt/aplite/diorite/chalk OK; emery falha à 1ª → `pebble kill` e repetir
pebble install --emulator basalt
```

- **"Connection refused" no install →** o Developer Connection na app Core do telemóvel desligou-se (acontece sozinho). Pedir ao Bruno para ligar.
- O wscript injeta `-Wno-error` (o GCC do SDK novo tem -Werror; há 2 warnings benignos).
- **Android:** `./gradlew assembleRelease` (JDK 21 instalado 2026-07-09; se o Gradle reclamar "does not provide JAVA_COMPILER", correr `./gradlew --stop` para matar a cache do daemon). O keystore (`release.keystore` + `keystore.properties`) está na raiz do repo, fora do git (copiado de `~/AndroidStudioProjects/ObsidianTasksPebbleBridge/`). Instalar: `adb install -r app/build/outputs/apk/release/app-release.apk` (o telemóvel está em adb wireless 192.168.1.66; release sobre release preserva os dados).

## Releases

- Atual: **v2.2** (package.json 2.2.0 / APK v2.2). Ver `release-v2.2/publish.sh` para o processo (push + GitHub release + assets; precisa `GITHUB_TOKEN` com contents:write — o token antigo foi exposto e deve ser rodado).
- Preferência do Bruno: trabalho novo substancial = **versão nova** (não substituir release existente). Rebble store: upload manual do .pbw.
- **v2.3 pendente** — conteúdo: fix alinhamento "New note" (36px, centrado), handler inbox-dropped, fix deletePin PKJS, setPackage/NOT_EXPORTED nos broadcasts Android, **substituto do Tasker (auto-abrir Obsidian)**. Estado 2026-07-09: Android compilado e instalado no telemóvel (versionName ainda 2.2 — bump por fazer); watchapp compilada mas POR INSTALAR (Developer Connection desligado). Falta: testar toggle no telemóvel, instalar watchapp, bump versões, sincronizar working copy → mirror, commit, release.

## Backlog (análise completa 2026-07-08, por prioridade)

**Feito hoje:** privacidade broadcasts ✔ · inbox dropped ✔ · deletePin órfão ✔ · alinhamento New note ✔

**Alto (invasivo — só com testes; a app FUNCIONA, não partir):**
1. Id estável por tarefa (hash URI+texto) em vez de INDEX/match-por-título — elimina marcar a tarefa errada quando o vault muda entre syncs.
2. Escritas de ficheiro: verificar sucesso do stream, voice notes em modo append `"wa"` (hoje reescreve o ficheiro todo; crash a meio = perda; converte CRLF→LF).
3. Spoofing: qualquer app pode forjar mensagens "do relógio" (receiver Pebble exportado é obrigatório) — validar/rate-limitar.

**Médio:** vault walk repetido por ação (2-4× SAF IPC) · `goAsync` estoura 10s em vaults grandes → WorkManager · `daysBetween` bug DST (usar java.time) · `checkForAppUpdate` re-dispara em cada rebind do pager · `cleanTitle` deixa texto de recorrência e não limpa tags acentuadas.

**Baixo:** `TaskCache.clear()` nunca chamado · código morto (`urgencyTag`, overload `getLines`) · dividir BackgroundReceiver/MainActivity · `parseRules` duplicado · testes só em TaskDates (adicionar: cleanTitle, isOpenTaskLine, joins CRLF, isNewer, ReminderStore round-trip) · watch: tuple type checks, strncpy sem terminador (l.380/403), clamp persist negativo, timers em deinit.

## Questões em aberto

- **Tasker: RESOLVIDO com a opção A** (toggle auto-abrir Obsidian, ver secção Android). O vault sincroniza via **Obsidian Sync pago** (confirmado pelo Bruno 2026-07-09), que só corre com a app aberta — daí o pop-up ao desbloquear. **Plano B se o pop-up irritar:** migrar para Syncthing (Syncthing-Fork no Android; substitui o Obsidian Sync, não coexiste com ele; telemóvel fica fresco mesmo bloqueado; Bruno pode cancelar a subscrição). Plugins do Obsidian NÃO conseguem sync em background no Android (a app congelada = plugins congelados) — beco sem saída, não investigar de novo.
- v2.3: fechar quando o relógio receber a build nova (Developer Connection) e o Bruno validar o toggle uns dias.
