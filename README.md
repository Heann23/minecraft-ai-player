# Minecraft AI Player

스스로 생각하고 움직이는 AI 플레이어를 서버에 접속시키는 Paper 플러그인입니다.
AI 는 진짜 플레이어처럼 서버에 들어와서 나무를 캐고, 도구를 만들고, 집을 짓고, 땅속에서 광물을 찾습니다.
최종 목표는 맨손에서 시작해 엔더 드래곤을 잡는 것입니다.

판단은 플러그인 안의 규칙으로 하며, 외부 AI 서비스(LLM)를 호출하지 않습니다.

## 지금 할 수 있는 것

- 나무 → 작업대 → 나무·돌 도구 → 화로 순으로 스스로 진행
- 벽·지붕·문·상자·화로·침대가 있는 집을 짓고, 상자에 물건을 보관하고, 밤에 잠자기
- 사냥해서 고기를 굽고, 배고프면 먹기
- 계단을 파 내려가 철을 찾고 제련해서 철 도구·방패·갑옷 만들기
- 다이아몬드를 찾아 다이아몬드 곡괭이 만들기
- 몬스터와 싸우거나 피하기 (방패로 화살과 크리퍼 폭발 막기, 감당이 안 되면 땅을 파고 숨기)
- 서버를 재시작해도 AI 와 가진 것, 집의 위치, 진행 상황을 그대로 이어 가기
- 채팅으로 이름을 불러 지금 하는 일과 이유 물어보기

네더, 엔드, 엔더 드래곤 전투는 아직 만드는 중입니다.

AI 는 순간이동하거나 아이템을 만들어 내지 않습니다. 걷고, 캐고, 놓고, 제작하는 모든 행동을 실제 플레이어와 같은 방식으로 합니다.

## 필요한 것

- Paper 26.3 서버 (다른 버전에서는 동작하지 않습니다. 서버 내부 코드를 사용합니다)
- Java 25

## 설치

1. [Releases](https://github.com/Heann23/minecraft-ai-player/releases) 에서 `MinecraftAI-x.y.z.jar` 를 받습니다.
2. 서버의 `plugins` 폴더에 넣고 서버를 시작합니다.

직접 빌드하려면:

```
gradlew build
```

`build/libs/` 에 JAR 가 만들어집니다.

## 사용법

명령어는 관리자(op)만 쓸 수 있습니다.

```
/ai spawn Bot      AI 를 만듭니다 (명령을 친 자리에 나타납니다)
/ai start Bot      스스로 움직이기 시작합니다
/ai stop Bot       멈춥니다
/ai remove Bot     없앱니다
```

AI 가 무엇을 하고 있는지 볼 때:

| 명령어 | 내용 |
|---|---|
| `/ai status [이름]` | 체력, 위치, 지금 하는 일 |
| `/ai why [이름]` | 지금 그 일을 하는 이유 |
| `/ai plan [이름]` | 지금 세워 둔 계획 |
| `/ai brain [이름]` | 최근의 판단 기록 |
| `/ai memory [이름]` | 기억하고 있는 것 (집, 상자, 광석 위치 등) |
| `/ai inv [이름]` | 인벤토리 보기 |
| `/ai perf [이름]` | 서버 틱에서 쓰는 시간 |

그 밖의 명령어:

| 명령어 | 내용 |
|---|---|
| `/ai spawn <이름> <스킨>` | 다른 계정의 스킨을 입혀서 만들기 |
| `/ai goal <목표\|auto> [이름]` | 한 가지 일만 하게 고정하기. `auto` 로 되돌립니다 |
| `/ai home <set\|clear> [이름]` | 지금 자리를 집으로 정하거나 지우기 |
| `/ai say <이름> <할 말>` | AI 가 채팅으로 말하게 하기 |
| `/ai save` | 지금 상태를 바로 저장하기 |
| `/ai debug <on\|off>` | 판단 과정을 서버 콘솔에 기록하기 |

## 설정

처음 실행하면 `plugins/MinecraftAI/config.yml` 이 만들어집니다. 자주 바꾸는 값:

| 설정 | 기본값 | 내용 |
|---|---|---|
| `ai.max-count` | 1 | 동시에 만들 수 있는 AI 수 |
| `ai.respawn-delay` | 60 | 죽은 뒤 다시 나타날 때까지의 시간 (틱) |
| `ai.show-goal-label` | true | 이름 위에 지금 하는 일 표시 |
| `combat.engage-range` | 16 | 이 거리 안의 몬스터를 상대할지 판단 |
| `combat.flee-distance` | 24 | 도망칠 때 벌리는 거리 |
| `performance.tick-budget-ms` | 5.0 | 한 틱에서 미뤄도 되는 일에 쓰는 시간 |
| `persistence.enabled` | true | 서버를 재시작해도 AI 를 되살릴지 |
| `chat.enabled` | true | AI 가 채팅으로 말할지 |

## 알아 둘 점

- AI 는 서버 접속 인원 한 명으로 셉니다.
- AI 주변의 청크가 로드된 채로 유지됩니다 (`ai.view-distance`).
- 아직 개발 중이라 AI 가 죽거나 한곳에서 헤맬 수 있습니다. 그럴 때는 `/ai why` 와 `/ai brain` 으로 이유를 볼 수 있습니다.

## Discord 음성 대화 (개발 중)

별도 `plugins/MinecraftAI/discord.yml`로 설정합니다. 기본값은 꺼져 있으며,
Discord 연결이나 로컬 제공자가 실패해도 게임 AI는 계속 실행합니다.
이 기능은 개발 브랜치의 초안이며 실제 봇·한국어 모델·음질·지연 시험은 아직 남아 있습니다.

1. 서버 실행 환경에 `MINECRAFTAI_DISCORD_TOKEN`을 설정합니다. 토큰은 설정 파일이나 저장소에 넣지 않습니다.
2. `discord.yml`의 `guild-id`, `voice-channel-id`를 따옴표로 감싼 ID로 지정합니다.
3. 로컬 [Ollama](https://docs.ollama.com/api/chat), [whisper.cpp 서버](https://github.com/ggml-org/whisper.cpp/tree/master/examples/server),
   [Piper HTTP 서버](https://github.com/OHF-Voice/piper1-gpl/blob/main/docs/API_HTTP.md)를 준비합니다.
   `providers.llm.model`에 설치한 한국어 대화 모델을 지정합니다. 모델을 자동 다운로드하지 않습니다.
4. `enabled: true`로 설정하고 서버를 재시작합니다. Java 실행 옵션에 `--enable-native-access=ALL-UNNAMED`를 추가합니다.

봇에는 지정한 일반 음성 채널의 보기·접속·발언·메시지 보내기 권한과 `applications.commands`가 필요합니다.
수신 안내와 관리 명령을 등록한 뒤 해당 채널에 접속하며, Stage 채널과 다른 서버·채널로 이동하지 않습니다.
"해리야"로 말을 걸고 후속 대화를 이어갑니다. LLM은 대화에만 사용하며 게임 행동을 실행하지 않습니다.
`conversation.greet-on-join`을 켜면 새 참가자에게 잠깐 기다렸다 짧게 인사합니다. 사람의 발화·인식·답변이 진행 중이면
뒤로 미루며, 잦은 재입장은 반복하지 않습니다. 인사를 모두 제출한 상대는 호출어 없이 답할 수 있습니다.

| Discord 명령 | 기능 |
|---|---|
| `/herry status` | 음성 연결과 기억 저장 상태 확인 |
| `/herry name name:<호칭>` | 본인의 이름·호칭 직접 확정 |
| `/herry speech allowed:<true 또는 false>` | 본인에게 반말할 허락 또는 거절 저장 |
| `/herry forget` | 본인의 기억과 임시 대화 문맥 삭제 |
| `/herry quiet`, `/herry listen` | 서버 관리자: 음성 수신·답변 중단 또는 재개 |
| `/herry leave`, `/herry resume` | 서버 관리자: 퇴장 또는 지정 채널 접속 재개 |
| `/herry backup`, `/herry backups` | 서버 관리자: 확정 기억 수동 백업·최근 백업 식별자 확인 |
| `/herry restore backup:<식별자> confirm:true` | 서버 관리자: 대화를 멈추고 선택한 기억 백업 복원 |

명령 회신은 본인에게만 보입니다. 음성 답변은 같은 채널의 참가자에게 들립니다.
복원은 현재 기억을 되돌리지만 삭제·정정 기록은 유지합니다. 성공·실패 후 모두 음성 수신은 중단되며,
`/herry listen`으로 직접 재개합니다. 복원 도중 재개하거나 개인 기억을 바꿀 수 없습니다.
원본 음성과 인식 전문은 파일로 저장하지 않습니다. 인식 신뢰도가 보정되지 않은 음성만으로
이름이나 말투 허락을 확정하지 않으며, 개인 명령으로 저장한 최소 기억은 재시작 후 복원합니다.
백업에서도 삭제 기록을 우선 적용합니다. `audio.minimum-rms`는 초기 잡음 구분 값이며 실제 마이크로 조정해야 합니다.
`audio.end-silence-millis`는 발화 종료까지 기다리는 무음 시간(기본 1000ms)입니다.
문장 사이에서 호출어가 잘리면 늘리고, 응답이 늦으면 줄여 보세요. 완전한 음성 활동 감지는 아직 후속 시험이 필요합니다.
