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
| `/ai plan [이름]` | 지금 세워 둔 계획 (목표를 "무엇을 얼마나"로 적은 것과 계획을 세운 스킬 포함) |
| `/ai observe [이름]` | 마지막으로 판단할 때 AI 가 알고 있던 것 전부 (몸, 가방, 환경, 진행, 기억) |
| `/ai learn [이름]` | 학습용 기록을 남기고 있는지, 후보 정책이 지금의 규칙과 얼마나 같은 판단을 했는지 |
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
| `learning.record` | false | AI 의 판단과 그 결과를 학습용 데이터로 남길지 |
| `learning.max-megabytes` | 512 | 남길 수 있는 총 크기. 넘으면 더 남기지 않음 |
| `learning.shadow-policy` | none | 행동은 그대로 두고 판단만 견주어 볼 후보 정책 (`none`, `repeat-last`) |

### 학습용 데이터

`learning.record` 를 켜면 `plugins/MinecraftAI/training-data/<AI 이름>/` 에 에피소드마다 파일 하나(`.jsonl`, 한 줄에 기록 하나)가 생깁니다.
에피소드는 자율 행동을 켠 때(또는 되살아난 때)부터 죽거나, 멈추거나, 서버가 꺼질 때까지입니다.

- `episode_start` / `episode_end`: 시작과 끝. 끝난 까닭(`DEATH`, `STOPPED`, `REMOVED`, `SHUTDOWN`, `ERROR`, `COMPLETED`)을 구분합니다.
- `decision`: 새 계획을 세운 판단 하나. 그때 알고 있던 것(`observation`), 지금의 규칙이 고른 목표(`teacher`),
  후보 정책이 골랐을 목표(`shadow`), 실제로 실행한 목표와 스킬과 행동들(`executed`)이 들어 있습니다.
- `action`: 그 계획의 행동 하나가 성공하거나 실패한 것.
- `outcome`: 그 계획이 어떻게 끝났는지(`SUCCEEDED`, `FAILED`, `INTERRUPTED`, `NO_PLAN`)와 목표의 완료 조건이 채워졌는지.
- `event`: 새로 이룬 항목(`MILESTONE`)과 단계 변화(`STAGE`).

기록은 AI 의 행동에 영향을 주지 않습니다. 파일은 별도 스레드가 쓰고, 쓰지 못하는 기록은 버립니다.
후보 정책은 관측만 받아서 답을 낼 뿐이고, 실제로 실행하는 것은 언제나 지금의 규칙이 고른 목표입니다.

## 알아 둘 점

- AI 는 서버 접속 인원 한 명으로 셉니다.
- AI 주변의 청크가 로드된 채로 유지됩니다 (`ai.view-distance`).
- 아직 개발 중이라 AI 가 죽거나 한곳에서 헤맬 수 있습니다. 그럴 때는 `/ai why` 와 `/ai brain` 으로 이유를 볼 수 있습니다.

## Discord 음성 대화 (개발 중)

별도 `plugins/MinecraftAI/discord.yml`로 설정합니다. 기본값은 꺼져 있으며,
Discord 연결이나 로컬 제공자가 실패해도 게임 AI는 계속 실행합니다.
이 기능은 개발 중인 초안입니다. 실제 Discord 마이크 입력·원격 답변 청취를 확인했고
반복 인사·끊김·지연 피드백을 반영했습니다. 추가 음성 시험은 사용자가 미뤘으며,
음질·다인 대화·게임 동시 실행과 최종 자연스러움 평가는 남아 있습니다.
해리는 음성 채널의 말만 듣고 음성으로 답합니다. Discord에 글로 쓰는 대화는 받지 않으며, `/herry` 명령은 관리와 내 설정에만 씁니다.

1. `discord.yml`의 `token`에 봇 토큰을 그대로 붙여 넣습니다(따옴표는 있어도 없어도 됩니다). 이 파일은 다른 사람에게 보내거나 공개 저장소에 올리지 마세요.
   토큰을 파일에 적고 싶지 않으면 `token`을 비워 두고 서버 환경 변수 `MINECRAFTAI_DISCORD_TOKEN`에 넣어도 됩니다. 둘 다 있으면 파일의 `token`을 씁니다.
2. `discord.yml`의 `guild-id`, `voice-channel-id`를 따옴표로 감싼 ID로 지정합니다.
3. 로컬 [Ollama](https://docs.ollama.com/api/chat) **0.35.1 이상**, [whisper.cpp 서버](https://github.com/ggml-org/whisper.cpp/tree/master/examples/server),
   [Piper HTTP 서버](https://github.com/OHF-Voice/piper1-gpl/blob/main/docs/API_HTTP.md)를 준비합니다.
   `providers.llm.model`에 설치한 한국어 대화 모델을 지정합니다. 모델을 자동 다운로드하지 않습니다.
4. `enabled: true`로 설정하고 서버를 재시작합니다. Java 실행 옵션에 `--enable-native-access=ALL-UNNAMED`를 추가합니다.

토큰이 새어 나갔거나 의심되면 Discord 개발자 포털의 봇 메뉴에서 Reset Token으로 재발급하고 `token`을 새 값으로 바꾸세요. 토큰은 콘솔 로그, 진단, 명령 응답, `/herry config`에 나오지 않습니다.
환경 변수를 쓸 때는 Windows 시작 메뉴에서 **환경 변수**를 검색하고 **시스템 환경 변수 편집 → 환경 변수 → 사용자 변수 → 새로 만들기**로
`MINECRAFTAI_DISCORD_TOKEN`을 추가합니다(값에는 토큰만, 따옴표 없이). 저장한 뒤 서버를 실행하는 터미널·런처를 다시 열어야 전달되고,
다른 이름을 쓰려면 `discord.yml`의 `token-env`를 그 변수 이름으로 바꿉니다. Windows 서비스로 실행할 경우 서비스 실행 계정의 환경에 설정해야 합니다.

시작이 실패하면 콘솔의 `Discord:` 진단을 확인하세요. `discord-token-missing`은 `token`이 비어 있고 환경 변수에도 값이 없다는 뜻, `discord-token-rejected`는 Discord가 토큰을 거부했다는 뜻(잘못 붙여 넣었거나 재발급됨), `discord-model-missing`은 `providers.llm.model`이 비어 있다는 뜻이고,
`discord-gateway-not-ready`는 로그인 후 준비 완료를 기다리지 못했다는 뜻입니다.
`discord-guild-unavailable`, `discord-voice-channel-unavailable`, `discord-voice-permissions-missing`은
각각 봇이 들어간 서버, 일반 음성 채널 ID, 채널 보기·접속·발언 권한을 확인해야 한다는 뜻입니다.
진단에는 토큰이나 대화 내용을 포함하지 않습니다.
`discord.yml` 자체의 문제는 다음 코드로 알려 줍니다. `discord-config-syntax`는 YAML 문법, `discord-config-size`는 64KB 초과,
`discord-config-unreadable`은 파일이 아니거나 읽을 수 없다는 뜻입니다. `discord-config-invalid-<섹션>`은 그 섹션의 값이 잘못됐다는 뜻이며
섹션은 `connection`(`enabled`·`token`·`token-env`·서버/채널 ID·`character-id`·`auto-connect`), `conversation`, `backup`, `audio`, `game`,
`llm`(`providers.llm`), `speech`(`providers.stt`·`providers.tts`)입니다. 서버·채널 ID를 따옴표 없이 적으면 `connection`이 나옵니다.
설정이 잘못돼도 게임은 그대로 실행되고 Discord만 시작하지 않으며, 진단에는 값이나 파일 내용을 넣지 않습니다.
운영 중 게이트웨이 연결 변화는 `discord-gateway-disconnected`·`discord-gateway-resumed`·`discord-gateway-recreated`로 남깁니다.
같은 코드는 콘솔에 30초에 한 번만 남기고 횟수는 모두 세어 `/herry diagnose`에서 볼 수 있습니다.

봇에는 지정한 일반 음성 채널의 보기·접속·발언·메시지 보내기 권한과 `applications.commands`가 필요합니다.
수신 안내와 관리 명령을 등록한 뒤 해당 채널에 접속하며, Stage 채널과 다른 서버·채널로 이동하지 않습니다.
"해리님, 안녕하세요"로 말을 걸고 후속 대화를 이어갑니다. 편하게 부를 때는 "해리야, 안녕"도 사용할 수 있습니다.
해리의 답변은 처음에는 존댓말이고 `/herry speech allowed:true`로 직접 허락하면 반말을 씁니다.
내 이름·말투·장난 설정과 기억 삭제는 모두 음성만으로 할 수 있습니다. `/herry name`·`/herry speech`·`/herry joke`·`/herry forget`으로도 같은 일을 할 수 있습니다.

- “해리야, 장난하지 마”, “존댓말로 해 주세요”처럼 **더 담백해지는 설정**은 바로 저장하고 짧은 확인 문장으로 답합니다.
- **잘못 들으면 곤란한 설정**은 해리가 들은 내용을 되묻고, 같은 사람이 바로 다음에 “네”(응·맞아요·좋아요 등)라고 답해야 적용합니다.
  - 이름: “제 이름은 민수예요”, “민수가 아니라 지수라고 불러 줘” → “민수님이라고 부르면 될까요?”
  - 반말 허락: “반말해도 돼”, “말 편하게 해” → “제가 반말로 편하게 말해도 된다는 말씀이죠?”
  - 장난 허용: “장난쳐도 돼”, “다시 장난해도 돼요” → “가벼운 장난을 해도 된다는 말씀이죠?”
  - 기억 삭제: “내 기억 지워 줘”, “내 이름만 잊어 줘” → “제가 기억하는 이름, 말투, 장난 설정을 모두 지울까요?”
- “아니요”라고 하거나 다른 말을 하면 아무것도 바꾸지 않습니다. 다른 사람의 “네”, 30초가 지난 답, 질문(“반말해도 돼?”)이나 남의 말을 옮긴 문장은 받지 않습니다.
이어지는 대화의 반복 인사·자기소개를 줄입니다. LLM은 대화에만 사용하며 게임 행동을 실행하지 않습니다.
`conversation.greet-on-join`을 켜면 새 참가자에게 잠깐 기다렸다 짧게 인사합니다. 사람의 발화·인식·답변이 진행 중이면
뒤로 미루며, 잦은 재입장은 반복하지 않습니다. 인사를 모두 제출한 상대는 호출어 없이 답할 수 있습니다.

| Discord 명령 | 기능 |
|---|---|
| `/herry me` | 모델 없이 본인의 이름·말투·장난 설정만 확인 |
| `/herry name name:<호칭>` | 본인의 이름·호칭 직접 확정 |
| `/herry speech allowed:<true 또는 false>` | 본인에게 반말할 허락 또는 거절 저장 |
| `/herry joke allowed:<true 또는 false>` | 본인에게 가벼운 장난을 허용하거나 중단하도록 설정 |
| `/herry forget [scope]` | 본인의 기억과 임시 대화 문맥 삭제. 범위를 고르지 않으면 전체, 호칭·말투·장난 설정 중 하나만 고를 수도 있음 |
| `/herry quiet`, `/herry listen` | 서버 관리자: 음성 수신·답변 중단 또는 재개 |
| `/herry leave`, `/herry resume` | 서버 관리자: 퇴장 또는 지정 채널 접속 재개 |
| `/herry diagnose` | 서버 관리자: 게이트웨이·음성·수신 상태, 마지막 백업 시각, 재시작 이후 진단 코드별 횟수(본문·ID·경로 없음) |
| `/herry config` | 서버 관리자: `discord.yml`이 유효한지, 실행 중인 설정과 달라 재시작이 필요한지만 확인(적용하지 않음) |
| `/herry backup`, `/herry backups` | 서버 관리자: 확정 기억 수동 백업·최근 백업 식별자 확인 |
| `/herry restore backup:<식별자> confirm:true` | 서버 관리자: 대화를 멈추고 선택한 기억 백업 복원 |

명령 회신은 본인에게만 보입니다. 음성 답변은 같은 채널의 참가자에게 들립니다.
명령이 실패하면 복원 진행 중, 없는 백업 식별자, 종료 중 등을 구분해 안내하며 내부 오류 문구는 보여 주지 않습니다.
“네”, 인용문·타인 설명·“반말해도 돼요?” 같은 질문이나 모델의 응답에서 허락을 추측하지 않습니다.
`/herry me`로 저장된 내 설정을 확인하고 `/herry forget`으로 본인 기억과 문맥을 함께 삭제할 수 있습니다.

모델 문맥이 넘치면 자동으로 질문이나 규칙을 잘라내지 않습니다. 오래된 대화·부가 기억만 줄여
최대 3번 요청하며 현재 질문·말투·장난 설정과 유효한 게임 자료는 유지합니다.
전체 요청은 `providers.llm.timeout-seconds` 안에서 끝나며, 그래도 넘치면 짧게 다시 질문해 달라고 안내합니다.

게임 상태를 공유하려면 `discord.yml`의 `game.target-ai`에 `/ai status`에 표시된 AI 이름을 지정합니다.
빈 값이면 공유하지 않으며 Discord 이름으로 게임 AI를 추측하지 않습니다. 상태는 서버에서 1초마다 읽고,
2.5초 이상 갱신되지 않은 자료는 현재 상태로 표시하지 않습니다. 다른 플레이어와 아이템 별명·메모·책 내용은 읽지 않습니다.
대화에는 유효한 최근 상태만 별도 자료로 전달하며, 대화가 게임 행동을 실행하지는 않습니다.
상태가 없거나 오래됐으면 자료를 보내지 않으며, 현재 아이템 개수를 과거 경험·아이템 별명과 구분합니다. 작은 모델의 게임 사실성 품질 검증은 아직 남아 있습니다.
“지금 뭐 하고 계세요?”, “어디 계세요?”, “다이아몬드 몇 개 가지고 있나요?” 같은 명확한 상태 질문은 모델을 호출하지 않고
최근 관측값으로 직접 답합니다. 자료가 오래됐으면 모른다고 답하며, 아이템 개수는 현재 인벤토리 칸에 있는 수량입니다.
이 경로는 게임 명령을 실행하지 않습니다. 일반 대화·여러 질문·복잡한 표현까지 모두 사실성 검증이 끝난 것은 아닙니다.
복원은 현재 기억을 되돌리지만 삭제·정정 기록은 유지합니다.
현재 사용자가 확정한 장난 설정은 과거 백업으로 되돌리지 않습니다.
성공·실패 후 모두 음성 수신은 중단되며 `/herry listen`으로 직접 재개합니다. 복원 도중 재개하거나 개인 기억을 바꿀 수 없습니다.
원본 음성과 인식 전문은 파일로 저장하지 않습니다. 인식 신뢰도가 보정되지 않은 음성만으로
이름이나 말투 허락을 확정하지 않으며, 직접 확정한 최소 기억은 재시작 후 복원합니다.
백업에서도 삭제 기록을 우선 적용합니다. `audio.minimum-rms`는 초기 잡음 구분 값이며 실제 마이크로 조정해야 합니다.
`audio.end-silence-millis`는 발화 종료까지 기다리는 무음 시간(기본 1000ms)입니다.
문장 사이에서 호출어가 잘리면 늘리고, 응답이 늦으면 줄여 보세요. 완전한 음성 활동 감지는 아직 후속 시험이 필요합니다.

## 행동 조합 API (개발용)

`AIPlayer`의 행동 제어 API는 정책이 관측을 읽고 행동과 도구를 골라 순서대로 실행할 수 있도록 만든 기반입니다.
모델을 훈련하거나 불러오지 않으며, 별도 요청이 없으면 기존 규칙 판단이 계속 실행됩니다.
프로그램은 기존 행동 실행 경로 하나를 사용하고, 기본적으로 완료·취소 후에는 규칙 판단으로 돌아갑니다.
게임의 생존 규칙·아이템 소비·도구 내구도·실제 이동과 채굴은 유지합니다.

| API | 역할 |
|---|---|
| `primitiveSnapshot()` | 월드·좌표·생존 상태·슬롯별 아이템과 내구도·인챈트를 값으로 읽기 |
| `primitiveContext()` | 기존 주변 인식에서 확인한 블록·엔티티의 제한된 목록과 관측 읽기 |
| `inspectPrimitiveTargets(positions)` | 정책이 고른 주변 블록 좌표를 제한된 개수만 추가 관측 |
| `checkPrimitiveCommand(command)` | 현재 상태에서 해당 명령이 가능한지와 거절 이유 확인 |
| `submitPrimitiveProgram(program)` | 버전·월드·관측 시점·시간 제한을 가진 행동 순서 제출 |
| `primitiveResult()` | 마지막 프로그램 결과와 각 행동 전후 상태·실제 목표 달성 여부 읽기 |
| `cancelPrimitiveProgram(reason)` | 진행 중인 프로그램을 취소하고 입력 정리 |
| `beginPrimitiveControl(idleTicks)` | 다음 정책 결정을 기다리는 제한된 제어 구간 시작 (`1..100` AI 틱) |
| `hasPrimitiveControl()` | 제한된 제어 구간이 아직 유지되는지 확인 |
| `endPrimitiveControl(reason)` | 제어 구간을 끝내고 규칙 판단으로 복귀 |

이 API의 호출은 서버 메인 스레드에서 합니다. 관측·명령·결과 객체에는 살아 있는 Bukkit 객체가 없으므로,
관측을 복사한 뒤 정책 계산만 다른 스레드에서 할 수 있습니다. 제출은 다시 서버 메인 스레드로 돌아와서 합니다.
각 행동 시작 직전에 같은 실행 조건을 다시 확인하므로, 이전에 가능했던 명령도 월드나 인벤토리가 바뀌면 거절될 수 있습니다.

기본적으로 한 프로그램이 끝나면 규칙 판단을 재개합니다. 여러 번 새 관측으로 행동을 선택하려면
먼저 `beginPrimitiveControl(idleTicks)`를 호출합니다. 이 제어 구간은 결과를 읽고 다음 정책 결정을 계산하는 동안
규칙 판단의 개입을 잠시 보류합니다. 계산은 다른 스레드에서 하더라도 다음 제출은 메인 스레드에서 하며,
제출 전에 `hasPrimitiveControl()`과 새 관측을 확인합니다. 대기 시간 만료·생존 위험·생명주기 변경·명시적 지시로
제어가 끝날 수 있고, 작업이 끝나면 `endPrimitiveControl(reason)`으로 해제합니다. 모델 실행이나 학습을 자동으로 시작하지 않습니다.

아래는 확인한 광석까지 이동하고, 선택한 섬세한 손길 곡괭이로 캐고, 드롭을 회수하는 예시입니다.
`ore`는 주변에서 확인한 광석 좌표이고, `oreDrop`은 그 광석의 아이템 이름입니다(예: `DIAMOND_ORE`).
`AIPlayer`, `BlockPoint`, `PrimitiveProgram`, `PrimitiveCommand`, `ToolChoice`, `TaskObjective`, `Submission`은 프로젝트의 Java API입니다.

```java
void mineKnownOre(AIPlayer ai, BlockPoint ore, String oreDrop) {
    var observed = ai.primitiveSnapshot();
    var slot = observed.inventory().stream()
            .filter(item -> item.material().equals("DIAMOND_PICKAXE"))
            .filter(item -> item.enchantments().getOrDefault("minecraft:silk_touch", 0) > 0)
            .findFirst().orElseThrow();
    var tool = new ToolChoice(slot.slot(), slot.material(), slot.enchantments());
    var program = new PrimitiveProgram(
            PrimitiveProgram.CURRENT_SCHEMA_VERSION,
            java.util.UUID.randomUUID(), observed.worldId(), observed.tick(),
            1800, "manual-policy-example",
            java.util.List.of(
                    new PrimitiveCommand.MoveTo(ore, 2.5),
                    new PrimitiveCommand.BreakBlock(ore, tool, true),
                    new PrimitiveCommand.Pickup(6.0)),
            new TaskObjective.AcquireItem(oreDrop, observed.itemCount(oreDrop) + 1));
    Submission submission = ai.submitPrimitiveProgram(program);
    if (!submission.accepted()) {
        // submission.reason()을 호출자에게 전달하거나 새 관측으로 다시 판단합니다.
    }
}
```

도구는 슬롯·재료·인챈트가 일치하는 아이템을 명시적으로 선택합니다. 같은 다이아몬드 곡괭이라도
섬세한 손길과 행운을 구분하며, 지정한 아이템이 바뀌면 다른 도구로 몰래 대체하지 않습니다.
빈 슬롯은 `AIR`·수량 `0`입니다. 장착이나 아이템 이동 후에는 슬롯이 바뀔 수 있으므로 새 관측으로 도구를 다시 고릅니다.
예시의 `BreakBlock(..., true)`는 채굴하는 동안 웅크리기를 유지합니다.

지원하는 명령은 이동·시선·점프·채굴·지정 위치 설치·음식 먹기·공격·드롭 회수·도구 장착·핫바 선택·제작·대기·웅크리기·수영·불필요한 아이템 버리기입니다.
프로그램은 스키마 버전 `1`, 최대 `32`개 행동, 최대 `2400`틱이며 제출할 관측은 `40` AI 틱 이내여야 합니다.
실행 시 이동 도착 반경은 `0.25..4`, 회수 반경은 `0.5..12`, 대기는 최대 `200`틱,
웅크리기는 최대 `200`틱, 수영은 최대 `400`틱, 수영 목표 거리는 최대 `16`블록으로 제한합니다.
실제 월드 접근과 생존 조건도 제한하며,
제출 성공은 행동이나 목표 달성 성공을 뜻하지 않습니다.

결과는 행동 상태와 실제 목표를 따로 기록합니다. 광석 블록을 부쉈더라도 아이템을 받지 못하면
`AcquireItem` 목표는 달성되지 않습니다. `OutcomeSignals`에는 경과 틱·전후 체력의 감소·사망·아이템 증감·목표 달성이 들어갑니다.
단일 보상 점수나 기존 규칙의 목표와 얼마나 같은 판단을 했는지를 성공 기준으로 정하지 않습니다.

상자 입출고·화로 투입과 회수·침대·동료 상호작용은 이 명령 API에 아직 연결하지 않았습니다.
양동이·활 등 모든 아이템 사용을 지원하지 않으며, `Eat`은 음식 먹기입니다.
제작·공격·드롭 회수는 기존 Action의 내부 판단을 사용하므로 모든 메뉴 조작이나 움직임을 정책이 직접 선택하는 단계는 아닙니다.
섬세한 손길 채굴 → 귀가 → 행운 처리 → 상자 보관과 같은 전체 인벤토리 작업 흐름을 자유롭게 학습하려면
남은 상호작용 계약과 실제 결과 시험이 더 필요합니다. 이 API 자체는 학습·데이터 수집·모델 실행 권한을 자동으로 켜지 않습니다.
