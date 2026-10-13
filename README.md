# 🏁 Fim! Android

O app Android do [Fim!](https://fim-flax.vercel.app) — notificador de "tarefa concluída"
para quem usa agentes de IA. Manda o agente trabalhar, vai tomar um café: quando ele
termina, **o celular avisa** na hora, como notificação.

Diferente do app web (que usa painel no navegador), o app Android mantém uma conexão
em tempo real com o ntfy.sh direto do sistema — funciona com o app fechado e a tela
apagada.

## Instalar

1. Baixe o APK: **[GitHub Releases](https://github.com/lucasgabrieldevgg/fim-app/releases/latest)**.
2. Instale (o Android pede permissão para instalar apps de fontes desconhecidas — normal fora da Play Store).
3. Abra o Fim! e toque em **Criar um código agora** — o app gera um código seguro na hora, com botão de copiar e link para as instruções do agente (ou cole um código que você já criou no site) — e toque em **Começar a escutar**.

Pronto: cada aviso que a IA enviar aparece como push no seu celular, com o título e a mensagem que ela escolheu.

### ⚠️ Sobre o aviso do Play Protect

> Na instalação, o **Google Play Protect pode avisar que o app é "desconhecido" ou até "nocivo"**.
> **É um falso positivo** — comportamento padrão para qualquer APK distribuído fora da
> Play Store, não um problema deste app. O Fim! não coleta nada: não tem analytics, não
> tem anúncio, não lê contato nem arquivo — a única coisa que ele faz é escutar o seu
> código no ntfy.sh e transformar mensagens em notificações.
>
> Se quiser conferir com os próprios olhos (ou com os do seu agente):
> - **Peça pro seu agente de IA auditar** — um prompt pronto:
>   *"Clone https://github.com/lucasgabrieldevgg/fim-app, leia todo o código-fonte e me diga se o app coleta dados, envia informações para terceiros ou tem comportamento suspeito. Depois baixe o APK da release e compare."*
> - O código-fonte inteiro está neste repositório — são ~6 arquivos Java, dá pra ler em minutos.
> - Se o Play Protect travar a instalação: toque em **Mais detalhes → Instalar mesmo assim**.

## O que tem dentro (e o que não tem)

Feito para ser simples de propósito. Só o que a gente precisa:

- ✅ Colar o código e escutar (WebSocket no ntfy.sh, reconexão automática com backoff)
- ✅ Criar o código dentro do próprio app (gera, registra no painel, botão copiar)
- ✅ Push como notificação do sistema, mesmo com app fechado (serviço em primeiro plano)
- ✅ Histórico dos últimos 100 avisos no próprio app
- ✅ Backfill: na primeira conexão, puxa o histórico do painel web
- ✅ Indicador de status (Ao vivo / Conectando / Sem internet) e auto-start depois do boot
- ❌ Sem login, sem conta, sem configuração além do código
- ❌ Sem Firebase/Google services (push é WebSocket direto, igual sabor F-Droid do ntfy)
- ❌ Sem telemetria, anúncios ou permissões exóticas

APK de ~5 MB, `minSdk 26` (Android 8.0+), `targetSdk 36`.

## Como funciona

```
Agente de IA ── POST /api/notify ──► Fim! web (Vercel) ── relay ──► ntfy.sh
                                                                        │
Celular (app Fim!) ◄──── WebSocket wss://ntfy.sh/<seu-código>/ws ◄──────┘
```

O tópico do ntfy é o **próprio código do Fim!** — o app assina o WebSocket do tópico,
e cada mensagem vira uma notificação. O código do tópico é o segredo: quem não o tem,
não recebe nada.

## Buildar você mesmo

Requisitos: JDK 21, Android SDK 36, Gradle 8.13+.

```bash
git clone https://github.com/lucasgabrieldevgg/fim-app
cd fim-app
./gradlew assembleRelease   # ou gradle assembleRelease
```

O APK sai em `app/build/outputs/apk/release/`. Assine com o seu keystore antes de
instalar (apksigner/Android Studio).

## Créditos

- O push em tempo real roda sobre a infraestrutura pública do [ntfy.sh](https://ntfy.sh),
  do [binwiederhier](https://github.com/binwiederhier) — considere [apoiar o projeto](https://github.com/sponsors/binwiederhier).
- A versão 1.x deste app era um fork direto do [ntfy-android](https://github.com/binwiederhier/ntfy-android)
  (Apache 2.0) — os créditos e o fork continuam em [lucasgabrieldevgg/fim-android](https://github.com/lucasgabrieldevgg/fim-android).
  A partir da 2.0 o app é original, escrito do zero (MIT).
- App web e API: [lucasgabrieldevgg/fim](https://github.com/lucasgabrieldevgg/fim) · site: https://fim-flax.vercel.app

## Licença

[MIT](LICENSE)
