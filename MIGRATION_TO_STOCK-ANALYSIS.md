# 저장소 이전 안내 (yoonmind → stock-analysis)

주식 분석 프로젝트는 `jsbeth-code/stock-analysis`로 이전 예정이다.
이 세션(yoonmind 기반)은 git 프록시가 세션 시작 시 지정된 브랜치(`claude/us-stock-analysis-v075sh`)로만 푸시를 허용하므로,
이전 작업은 **stock-analysis를 소스로 생성한 새 세션**에서 수행한다.

## 새 세션에서의 이전 절차 (Claude용)

1. 이 세션의 소스 저장소가 `jsbeth-code/stock-analysis`인지 확인한다 (푸시 가능 대상).
2. `jsbeth-code/yoonmind`가 세션 소스에 없으면 `add_repo`로 추가하고 클론한다.
3. yoonmind 클론에서 전체 이력을 확보한다: `git fetch --unshallow origin claude/us-stock-analysis-v075sh` (얕은 클론인 경우).
4. stock-analysis 작업 디렉토리에 yoonmind를 리모트로 추가하고, `claude/us-stock-analysis-v075sh`의 전체 이력을 이 세션의 지정 브랜치로 푸시한다.
   - main 직접 푸시가 프록시에 막히면: 지정 브랜치에 푸시 → GitHub MCP로 PR 생성 → 병합으로 main 반영.
   - 원격 main의 초기 커밋(README)과 히스토리가 무관해 거부되면 `--allow-unrelated-histories`로 병합 후 푸시.
5. 이전 완료 후:
   - `.claude/skills/us-stock-analysis/SKILL.md`의 저장소·브랜치 언급을 stock-analysis 기준으로 갱신
   - 새 보고서는 전부 stock-analysis의 `analysis/reports/`에 저장
   - yoonmind는 아카이브로 유지 (더 이상 푸시하지 않음)
6. 검증: stock-analysis의 main(또는 지정 브랜치)에 `analysis/reports/` 보고서 42개 파일 + 커밋 이력이 보이는지 확인.

## 참고
- GitHub 앱 권한은 두 저장소 모두 확인 완료 (2026-07-21, API 쓰기 테스트 성공).
- 이 파일은 이전 완료 후 삭제해도 된다.
