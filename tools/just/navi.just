# apps/navi_proto — 프로토타입 서버(파이썬) 레시피 (MZ2AZ-293).
#
# Bazel 을 거치지 않는 유일한 실행 경로다 — 프로토타입이라서다(모듈 README
# 머리말). 정식 이관(MZ2AZ-283·284·285) 때 이 파일은 사라진다.

# 프로토타입 서버를 띄운다 (:8899). 콘솔에 붙어 요청 로그를 찍는다.
navi-run:
    cd apps/navi_proto && ./run.sh

# 로컬 LLM 을 띄운다 (:8900). 첫 20~40초는 모델 적재라 응답이 없다.
navi-llm:
    cd apps/navi_proto && ./llm.sh

# 준비 상태 한눈에 — 서버·LLM 이 응답하는가.
navi-health:
    @curl -s -o /dev/null -w "navi(:8899) %{http_code}\n" localhost:8899/api/poi-categories || echo "navi(:8899) DOWN"
    @curl -s localhost:8900/v1/models | grep -q '"id"' && echo "llm(:8900) ready" || echo "llm(:8900) 안 떠 있음(적재 중이거나 꺼짐)"
