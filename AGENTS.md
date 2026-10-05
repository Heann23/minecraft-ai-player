# Minecraft AI Player 개발 지침

## 목표와 환경

Paper 서버에서 실제 플레이어처럼 생존하는 자율 AI 플러그인이다. 맨손에서 시작해
자원 수집, 제작, 집 짓기, 채굴, 전투를 거쳐 엔더 드래곤 처치를 목표로 한다.
네더·엔드·드래곤 전투는 개발 중이다. README와 실제 구현을 함께 확인한다.
핵심 게임 판단은 규칙 기반이며, 명시적인 요청 없이 외부 LLM에 의존하도록 바꾸지 않는다.

- Java 25, Gradle Kotlin DSL, Gradle Wrapper 9.7.1
- Paperweight userdev 2.0.0-beta.24, Paper dev bundle 26.3.build.140-beta
- 기본 패키지: `me.herry.minecraftAI`
- 플러그인 진입점: `src/main/java/me/herry/minecraftAI/MinecraftAI.java`
- 설정과 플러그인 메타데이터: `src/main/resources/config.yml`, `plugin.yml`
- 테스트: `src/test/java`, JUnit Jupiter 5

버전의 기준은 `build.gradle.kts`와 `gradle/wrapper/gradle-wrapper.properties`다.
NMS를 사용하므로 다른 Paper 버전의 호환성을 임의로 가정하거나 의존성을 바꾸지 않는다.

## 코드 구조

`src/main/java/me/herry/minecraftAI/`를 기준으로 다음 역할을 유지한다.

- `MinecraftAI`: 플러그인 시작·종료, 설정 및 통신·저장·컨트롤러 구성.
- `ai/AIController`: AI 생성·제거, 틱 실행, 사망·리스폰, 저장·복원 관리.
- `ai/AIPlayer`, `AIServices`: AI 한 명의 상태와 인식·이동·인벤토리·기억 등의 시스템 연결.
- `ai/AIBrain`, `SituationBuilder`, `ai/brain/`: 인식 → 상황 → 목표 선택 → 계획 →
  행동 실행 → 결과·실패 회복 → 재계획. 판단 이유는 `DecisionLog`에 남긴다.
- `ai/goal/GoalSystem`, `GoalType`: 지금 수행할 단기 목표 선택.
- `ai/plan/Progression`, `ai/goal/Milestone`, `Stage`: 게임 진행 단계와 다음 이정표 판단.
- `ai/plan/Planner`와 개별 계획 클래스: 목표를 `ai/action/Action`의 순서로 변환.
- `ai/AIBody`, `nms/FakePlayerBody`, `FakePlayer`: 실제 NMS 플레이어와 행동 입력.
- `ai/perception/`, `navigation/`, `memory/`, `world/`: 인식, 경로 탐색, 기억, 월드 모델.
- `ai/perf/WorkBudget`, `TickProfiler`: AI들이 공유하는 틱 예산과 성능 측정.
- `ai/comm/CommunicationHub`, `ChatChannel`, `LanguageInterpreter`: 대화 수신·해석·응답 경계.
  현재 main의 등록 채널은 `InGameChatChannel`이다.
- `ai/AISnapshots`, `persist/AISnapshot`, `AIStateStore`, `YamlStateStore`: 상태 캡처와
  `plugins/MinecraftAI/players/<name>.yml` 저장·복원.
- `config/AIConfig`, `ConfigRules`: 설정 읽기와 값 검증.
- `commands/`, `events/`, `view/`: 명령, 서버 이벤트, 인벤토리 표시.

## 유지할 원칙

1. 이동·채굴·설치·제작·전투는 기존 몸체 입력과 Action을 사용한다. 자율 진행을
   쉽게 만들려고 순간이동, 아이템 생성, 채굴·제작 생략 또는 생존 규칙 우회를 추가하지 않는다.
   생성·리스폰·상태 복원 등 기존 생명주기 동작은 별도로 구분한다.
2. 새 행동은 기존 `GoalType → GoalSystem → Planner → Action` 구조에 연결한다.
   진행 조건은 Progression/Milestone, 주변 정보는 Perception/MemorySystem/WorldModel을 우선 사용한다.
   AIBrain에 중복된 대형 조건문이나 독립적인 두뇌를 만들지 않는다.
3. Bukkit/Paper/NMS 월드·엔티티 접근과 CommunicationHub 호출은 서버 메인 스레드에서 한다.
   파일 I/O·외부 네트워크·월드 객체와 무관한 계산만 비동기로 처리하고, 게임 반영은
   메인 스레드로 돌려보낸다. 외부 대화·음성 처리가 서버 틱을 차단해서는 안 된다.
4. 블록 검색과 경로 탐색의 WorkBudget, 개수 제한, 여러 틱에 걸친 처리를 유지한다.
   매 틱 큰 범위나 전체 월드를 동기적으로 훑는 코드를 추가하지 않는다.
5. 저장 변경은 메인 스레드의 스냅샷 캡처와 파일 쓰기를 구분한다. 최신 저장을 오래된
   저장이 덮어쓰거나, 삭제된 AI가 지연 저장으로 되살아나지 않게 한다.
   임시 파일 교체와 종료 시 저장 동작을 보존한다.
6. Discord/STT/LLM/TTS는 통신 adapter/service로 분리한다. 게임 판단 코드에
   외부 구현을 직접 연결하지 않는다. 미완료 PR의 코드를 완성된 기능으로 소개하지 않는다.
7. 기본 `ai.max-count`는 1이다. 기존 TeamChat과 여러 AI 지원은 이유 없이 제거하거나 깨뜨리지 않는다.

## 변경과 검증

수정 전 관련 구현과 테스트를 읽고 기존 기능을 재사용한다. 요청 범위에 필요한 변경만 한다.
기능과 버그 수정에는 동작을 검증하는 단위·회귀 테스트를 추가하거나 갱신한다.
문서만 바꾸는 작업에는 의미 없는 테스트를 만들지 않는다. 기존 테스트를 삭제하거나 약화시켜
실패를 숨기지 않는다. 빈 catch, 임시 fallback 또는 TODO로 미구현 문제를 감추지 않는다.
설정을 추가하면 `config.yml`, `AIConfig`, `ConfigRules`와 관련 테스트를 함께 확인한다.

전체 빌드(단위 테스트 포함):

```sh
# Linux / CI
bash ./gradlew build "-PpluginsDir=" --no-daemon
```

```powershell
# Windows PowerShell
.\gradlew.bat build "-PpluginsDir=" --no-daemon
```

단위 테스트만 실행할 때는 같은 명령의 `build`를 `test`로 바꾼다.
`-PpluginsDir=`는 로컬 서버에 JAR가 자동 배포되지 않도록 명시적으로 비운다.
CI의 기준은 기존 `.github/workflows/ci.yml`의 Java 25 `build` job이다. 중복 CI를 만들지 않는다.
Paper/NMS 동작을 바꾸면 환경이 있을 때 관련 서버 시나리오도 확인한다.
서버 환경이나 로컬 빌드 환경이 없으면 실행하지 못한 검증과 이유를 PR에 명시하고,
로컬 검증과 GitHub CI 결과를 구분해서 보고한다. 실행하지 않은 검증을 성공이라고 쓰지 않는다.

## 브랜치·PR·자동 병합

사용자가 저장소 변경 작업을 요청하면 권한이 있는 범위에서 구현부터 PR과 CI 확인까지 진행한다.
명시적인 사용자 제한이 있으면 그 제한을 따른다.

1. 최신 `origin/main`을 확인하고 별도 브랜치(`codex/<작업명>` 또는 `claude/<작업명>`)에서 작업한다.
   main에 직접 commit/push하지 않는다. 기존 작업 브랜치를 이어갈 때는 변경을 보존하며 main을 반영한다.
   사용자 커밋을 임의로 되돌리거나 force-push하지 않는다.
2. 관련 검증과 전체 build를 수행하고 diff를 검토한다. 요청한 파일만 명시적으로 stage하여
   commit하고 작업 브랜치를 origin에 push한다.
3. main 대상 PR을 생성한다. 제목과 설명은 한국어를 기본으로 하며 변경 이유·동작,
   검증 결과, 미검증 항목과 제한을 적는다. 작업이 미완료면 Draft로 유지한다.
4. CI가 실행되는지와 최신 커밋의 결과를 확인한다. Draft, 충돌, 실패한 검사,
   미해결 차단 리뷰가 있으면 병합하지 않는다. 단순히 CI가 통과했다는 이유로
   사용자의 미완료 Draft PR을 Ready로 바꾸지 않는다.
5. 저장소에 main 대상 PR 필수 및 CI `build` 필수 규칙이 실제로 적용되어 있고,
   최신 main과 호환되며 작업이 완료된 PR에만 GitHub Auto-merge를 Squash 방식으로 설정한다.
   CI 진행 중에는 Auto-merge로 예약하고 GitHub가 필수 검증 통과 후 병합하도록 한다.
   필수 규칙이나 Auto-merge 권한이 없으면 PR까지 완료하고 필요한 설정을 보고한다.

GitHub CLI 사용 예(현재 PR 번호와 확인한 head SHA를 지정):

```sh
gh pr merge <PR번호> --auto --squash --match-head-commit <확인한-head-SHA>
```

보호 규칙·ruleset을 우회하지 않는다. `--admin`을 사용하거나 필수 검사를 끄지 않는다.
병합 후 원격 작업 브랜치는 저장소의 자동 삭제 설정으로 정리한다.
이 지침은 에이전트의 작업 절차다. GitHub 보호 규칙을 대신하거나, 모든 PR의 Auto-merge를
자동으로 켜는 Actions 워크플로가 아니다. 에이전트가 완료된 PR마다 Auto-merge를 설정한다.

완료 보고에는 commit/push, PR 링크, 최신 CI, Auto-merge 예약 또는 병합 여부를 구분해 적는다.
권한·환경 제한 때문에 남은 단계는 이유와 필요한 조치를 명확히 적는다.

## 릴리스 태그와 GitHub 릴리스 (필수)

버전을 올리는 릴리스 작업은 PR 병합만으로 완료하지 않는다. Codex와 Claude Code 모두
다음 절차를 반드시 수행한다. 문서·CI·개발 지침만 바꾸고 버전이 같으면 새 릴리스를 만들지 않는다.

1. 릴리스 PR이 main에 병합됐고 필요한 검증이 성공했는지 확인한다. Draft나 미병합
   작업 브랜치에는 정식 릴리스 태그를 붙이지 않는다.
2. 해당 PR의 정확한 main 병합 커밋에서 `build.gradle.kts`의 버전을 읽고
   `v<버전>` 태그(예: `v1.1.9`)를 만든다. main의 최신 커밋을 무조건 쓰지 않는다.
   기존 태그가 있으면 커밋 일치를 확인하고, 태그를 강제로 옮기거나 덮어쓰지 않는다.
3. 그 커밋을 별도 체크아웃에서 `build "-PpluginsDir=" --no-daemon`으로 검증하고
   `build/libs/MinecraftAI-<버전>.jar`를 준비한다. 다른 버전의 JAR를 재사용하지 않는다.
4. 태그를 origin에 push하고 같은 태그의 GitHub 릴리스를 생성한다. 한국어 설명에
   변경 사항, 관련 PR, 실제 검증 결과, 미검증 항목·알려진 문제를 적고 해당 JAR를 첨부한다.
   기존 릴리스가 있으면 내용을 확인하며 중복 생성하거나 자산을 임의로 덮어쓰지 않는다.
5. 원격 태그의 커밋, GitHub 릴리스와 JAR 첨부를 확인하고 링크를 완료 보고에 포함한다.
   과거 버전을 뒤늦게 발행할 때는 최신 릴리스 표시를 더 오래된 버전으로 바꾸지 않는다.

빌드 실패·미완료 기능·배포 권한 부족으로 발행하지 못하면 릴리스 완료라고 보고하지 않는다.
서버 시나리오 검증이 남았거나 알려진 문제가 있으면 명확히 공개하고 안정성이 확인됐다고
주장하지 않는다. main에 병합된 원격 작업 브랜치는 자동 삭제하고, 아직 병합하지 않았거나
후속 작업에 필요한 브랜치는 유지한다. 브랜치 삭제 전에 코드가 main에 보존됐는지 확인한다.
