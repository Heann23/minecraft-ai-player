# Claude Code 프로젝트 지침

@AGENTS.md

루트 `AGENTS.md`가 공통 개발 규칙이다. 프로젝트 구조, 서버 스레드·틱 예산·저장 안정성,
검증 명령, 별도 브랜치와 PR, CI 필수 검사 및 Squash Auto-merge 절차를 모두 따른다.

요청한 변경은 관련 코드 분석 → 구현 → 검증 → diff 검토 → commit → push → PR →
CI 확인 → 완료된 PR의 Auto-merge 설정까지 권한이 있는 범위에서 진행한다.
미완료 Draft PR과 기존 사용자 작업을 보존하고 보호 규칙을 우회하지 않는다.
실행하지 못한 검증과 권한이 없는 단계는 결과 보고에 명시한다.
