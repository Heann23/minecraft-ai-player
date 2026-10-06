# Goal 선택 학습용 경험 준비

개발자용 Python 표준 라이브러리 도구다. 사용자 서버에 Python 설치나 학습을 요구하지 않는다. 모델을 훈련하거나 플러그인의 판단을 바꾸지 않는다.

```powershell
python export_experience.py <training-data-folder> --goals <matching-source>/GoalType.java --output <new-output-folder>
```

현재 Experience/Observation v1만 받는다. 에피소드 시작·종료, 판단 ID, 실제 행동·결과 연결, 기록 개수를 확인한다. 손상·지원하지 않는 스키마·연결 누락이 있는 에피소드는 통째로 제외하고 오류를 남긴다. 종료되지 않은 수집 중 파일은 최종 데이터로 사용하지 않는다. 오류가 있으면 종료 코드 2를 반환한다. 기존 출력 폴더를 덮어쓰지 않는다.

자율 Teacher 판단만 Goal 선택 표본으로 내보낸다. 사용자 요청·강제 목표, 갇힘 회복용 대체 계획, Teacher와 실제 실행 목표가 다른 표본은 제외하고 개수를 보고한다. 입력은 결정 당시 Observation이다. Teacher 점수·이번 실행 계획·미래 결과·Shadow 출력을 입력에 섞지 않는다. 실패·중단 판단의 라벨은 성공으로 바꾸지 않으며 `result` 메타데이터로 구별한다. 훈련 코드는 이 메타데이터를 feature로 쓰면 안 된다.

`goal-ids.json`은 해당 플러그인 소스의 enum 순서를 고정한 출력 대응표다. `train.jsonl`, `validation.jsonl`, `test.jsonl`은 seed 문자열 SHA-256의 앞 8자 정수 modulo100으로 70/15/15 버킷을 배정한다. 같은 seed의 모든 AI·재실행·리스폰은 같은 쪽에 속한다. 이는 데이터 누출을 막는 배정 규칙이며 실제 표본 비율이나 미사용 월드 평가 성공을 보장하지 않는다. 개발 서버의 동일 시드에 모두 배정된 작은 시험 자료는 모델 평가군이 아니다.

`audit.json`에 원본 해시·스키마·버전·seed·종료 원인·제외/오류·Goal/결과/분할 표본 수를 남긴다. 입력 파일 경로와 AI 이름은 산출물 메타데이터에 복사하지 않는다. Observation에는 AI 좌표와 월드 상태가 포함되므로 실제 자료는 공개 저장소에 추가하지 않는다.

현재 `readyForTraining`은 항상 false다. 충분한 서로 다른 seed와 Goal 표본, 고정 평가군, 비학습 기준선 비교가 필요하다. 이후 첫 목표 선택 모방학습·ONNX export·Java Shadow 연결로 확장한다. Goal 선택 모델이 Skill 내부 동작까지 학습했다고 보고하지 않는다.

숫자 입력 준비:

```powershell
python prepare_features.py <export-output-folder> --output <new-feature-folder>
```

`goal_features.py`는 Observation v1의 몸 상태·인벤토리·환경·진행·기억과 판단 직전 목표를 고정된 순서로 변환한다. 연속값은 계약에 적힌 상수로 나누고 0~1로 제한한다. 학습·평가 자료 전체에서 평균이나 분산을 추정하지 않는다. 범주값은 명시된 순서의 one-hot이다. 절대 좌표·tick·시각·seed·이름·자유문자열·Teacher 점수·미래 결과는 입력에 포함하지 않는다. Observation의 모든 세부 정보가 표현되는 모델은 아니며, 생물 종류·장비/효과·바이옴 등은 현재 제외한다.

없는 아이템 종류의 개수는 0이다. 거점을 모르면 거점 거리도 0으로 표현하고 별도 `homeKnown` 값으로 구분한다. 나머지 선택 필드가 없거나 숫자가 유한하지 않거나 범주가 지원 목록 밖이면 거부한다. 새 Observation 스키마나 Goal 순서가 나오면 기존 계약을 조용히 재사용하지 않는다.

`feature-contract.json`에는 순서·정규화 상수·Goal 대응표·원본 Goal 소스 해시가 들어 있다. `feature-report.json`에는 계약 해시·입력 분할 파일 해시·표본/seed/Goal 개수·오류가 들어 있다. seed 분할이나 감사 계약이 맞지 않는 자료는 오류 종료하며 부분 학습 자료를 생성하지 않는다. 숫자 파일의 `x`만 입력이며 라벨·seed·원본 해시·결과는 분할/평가 메타데이터다. 변환 성공은 학습 완료나 모델의 성능을 뜻하지 않는다.
