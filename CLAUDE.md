# Instruções do projeto: wifi-analyzer

Este repositório é o analisador de redes Wi-Fi (Python, só biblioteca padrão, Linux com `iw`).
Os padrões abaixo são compartilhados com os outros projetos.

## Preferências do dono do projeto
- Responder sempre em português.
- Criar um arquivo `.md` com as informações do projeto (aqui: `PROJETO.md`) e mantê-lo atualizado.
- Cada projeto tem seu próprio repositório Git separado.
- Fazer commit e push ao concluir mudanças, e lembrar o dono de fazer isso quando houver algo pendente.

## Repositórios
- `esmeraldorg-alt/wifi-scanner` — este projeto (scanner Wi-Fi em Python + app Android).
- `esmeraldorg-alt/gerador-cartazes` — gerador de cartazes (PWA).
- `esmeraldorg-alt/domino-do-meladinha` — jogo de dominó, com APK via GitHub Actions.
- Repositórios novos só podem ser criados pelo dono no GitHub. Para usar um repositório existente numa sessão, usar `add_repo` (com `access: "push"` para escrever).

## Padrão de APK (Android nativo, sem Android Studio)
Copiado de `domino-do-meladinha`:
- Código em `android/` (Gradle 8.5.2 plugin, compileSdk 34, minSdk 26, Java 17, `gradle.properties` com `android.useAndroidX=false`).
- Assinatura: `android/debug.keystore` é criado uma vez pelo próprio workflow e commitado. Senha `android`, alias `androiddebugkey`.
- `.github/workflows/apk.yml` roda no push em `main` quando `android/**` muda. Compila com `gradle assembleRelease` (Gradle 8.9 via `setup-gradle`), e publica uma release `apk-<run_number>` com `--latest`.
- Link fixo para o APK mais recente: `https://github.com/<dono>/<repo>/releases/latest/download/<Nome>.apk`.
- O ambiente de sessão não tem Android SDK: a validação do build é feita pelo GitHub Actions. Acompanhar com `mcp__github__actions_list` (list_workflow_runs) e logs com `mcp__github__get_job_logs`.
