# Goal 선택 경험 준비와 개발용 모델 후보

개발자용 경험 준비·오프라인 후보 학습·내보내기 도구다. 사용자 서버에 Python 설치나 학습을 요구하지 않는다. 플러그인의 실제 판단과 실행권을 바꾸지 않는다. 경험 준비와 첫 후보 학습은 표준 라이브러리만 쓰고 ONNX 내보내기는 선택적 개발 의존성을 사용한다.

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

첫 개발용 모방학습 후보:

```powershell
python train_goal_bc.py <feature-folder> --output <new-candidate-folder>
```

표준 라이브러리로 작은 선형 softmax 모델을 훈련한다. 고정된 난수 시드·80 epoch·학습률 0.05를 사용하고 성공한 train 표본만 가중치 갱신에 넣는다. 실패·중단·계획 없음의 제외 개수를 기록하며 이를 성공으로 바꾸지 않는다. 클래스 빈도의 제곱근 역수로 학습률을 가중한다. validation/test는 가중치 갱신·정규화·하이퍼파라미터 선택에 사용하지 않는다. 지원 Goal은 성공한 훈련 표본에 존재한 종류만이다.

`candidate.json`은 실제 학습한 가중치·bias·지원 Goal ID·입출력 계약·학습 방법을 담는다. `evaluation.json`은 파일/계약/가중치 해시, 훈련 seed·표본·지원/미지원 Goal과 각 분할의 Teacher 일치율을 담는다. 다수 클래스와 직전 Goal 반복 기준선도 비교한다. Teacher 일치율은 게임 생존 성공률이나 드래곤 처치 능력이 아니다. 평가에서 훈련되지 않은 Goal도 오답으로 계산한다. 이 후보는 항상 `deployable: false`이며 자동 배포하지 않는다.

현재 Java 추론·Shadow 연결은 없다. 모델 누락/불일치 fallback·지연/안전·미사용 월드 실제 평가를 구현해야 한다. 게임 서버의 Teacher 실행권과 기본 설정은 이 도구로 바뀌지 않는다. raw 경험·숫자 데이터·가중치 산출물은 공개 Git에 포함하지 않는다.

ONNX 후보 내보내기와 실제 CPU 추론 비교:

```powershell
python -m pip install -r requirements-onnx.txt
python export_goal_onnx.py <candidate-folder> --features <frozen-feature-folder> --output <new-model-folder>
```

`export_goal_onnx.py`는 같은 후보/입출력 계약/훈련·평가 파일 해시를 확인하고 ONNX opset13·IR8의 MatMul/Add/ArgMax 그래프를 만든다. 입력은 float32 `[batch, featureCount]`, 출력은 float32 logits와 int64 Goal ID다. 미학습 Goal은 가중치를 0으로 하고 bias -1e9로 마스킹해 지원 Goal만 고른다. 실제 CPU 추론에서 고정 자료의 Goal ID와 지원 Goal logit을 원본 후보와 비교하며, 불일치가 있거나 절대 오차가 1e-4를 넘으면 실패 종료한다.

모델·원본 후보·feature/Goal·분할·도구 해시, 런타임 버전·OS·CPU provider·입출력·지원 ID·오차와 단일 입력 지연을 `model-manifest.json`에 남긴다. 산출물은 `goal-candidate.onnx`와 계약/manifest다. 수치 동등성은 후보 품질 개선이나 Java/Paper 호환성을 증명하지 않는다. 이 단계도 `deployable: false`, `executionAuthority: none`이며 JAR에 모델을 넣거나 자동 승격하지 않는다. Java 추론·Shadow·fallback·지원 환경 검증은 후속이다.

API 근거: [ONNX 공식 Python 문서](https://onnx.ai/onnx/intro/python.html), [ONNX Runtime 공식 Python API](https://onnxruntime.ai/docs/api/python/api_summary.html).
