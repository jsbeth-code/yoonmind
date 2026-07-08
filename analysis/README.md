# 미국 주식 종합 분석 프로젝트

미국 주식/산업을 6명의 전문가 관점으로 반복 분석하는 Claude Code 프로젝트입니다.

## 사용법

Claude Code 세션(이 저장소)에서:

```
/us-stock-analysis NVDA
/us-stock-analysis 전력산업 밸류에이션 낮고 AI 둔화에도 방어되는 종목만
/us-stock-analysis DUK 지난 보고서와 비교해서 업데이트
```

## 분석 프레임 (6-전문가)

1. **섹터 분석가** — 산업 구조, 진입장벽, 유망 기업 지도, 정치·경제 리스크
2. **뉴스 분석가** — 주가에 영향을 주는 최근/과거 뉴스
3. **기업 분석가** — 해자, CEO, 성장성, 밸류에이션 + 유명 애널리스트 의견 비교·비판
4. **재무 전문가** — 재무제표, 계약, 신용지표, 배당, 장래 전망
5. **기술적/퀀트 분석가** — MACD, RSI, 하이킨애시, 이평, 매수·매도 타이밍
6. **종합 판단** — 강력매수/매수/보유/매도 결론 + 시나리오별 대응

## 결과물

- 한글 PDF 보고서 (채팅으로 전달)
- jsbeth@gmail.com Gmail 초안 (요약 HTML 본문; 발송 버튼은 직접)
- `reports/` 폴더에 보고서 이력 축적

## 구조

```
.claude/skills/us-stock-analysis/SKILL.md   # 분석 워크플로우 정의 (Claude가 따르는 지침)
analysis/
  templates/report_template.html            # 보고서 HTML 템플릿 (한글 최적화)
  scripts/build_pdf.sh                      # HTML → PDF 변환 (headless chromium + Noto CJK)
  reports/                                  # 완성된 보고서 아카이브
```

## 참고: 유료 리서치 반영

모틀리풀/시킹알파/코이핀 등 유료 자료는 Claude가 직접 로그인할 수 없으므로,
필요한 내용을 복사해 채팅에 붙여넣으면 분석에 교차검증·반영됩니다.
