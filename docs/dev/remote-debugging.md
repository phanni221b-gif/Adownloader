# 실제 재생 네트워크 독립 검증 절차 (GeckoView 원격 디버깅)

ADownloader의 WebExtension 관찰과 **무관하게**, Firefox 개발자도구로 실제 재생 네트워크를 직접 보는 절차입니다.
정식 릴리스 APK에서는 원격 디버깅이 꺼져 있으며(CI가 `DEBUG = false`를 강제), 이 절차는 **진단용 빌드에서만** 동작합니다.

## 0. 진단용 APK 받기

1. GitHub 저장소 → **Actions** → **Diagnostic APK (remote debugging)** → **Run workflow** (branch: `main`).
2. 완료되면 실행 결과 하단 **Artifacts** 의 `Adownloader-diagnostic-arm64` 를 PC로 내려받아 압축을 풉니다.
3. PC에서 설치: `adb install app-diagnostic.apk`

- 패키지명은 `com.adownloader.app.diag`(버전 `0.8.7-diag`)라서 **기존 정식 앱을 덮어쓰지 않고 따로 설치**됩니다. 정식 앱의 기록·설정은 그대로입니다.
- 아이콘과 이름이 같습니다. 설정 → 애플리케이션에서 버전 `-diag` 로 구분하세요. 검증이 끝나면 진단용 앱만 삭제하면 됩니다.

## 1. 기기 준비 (Fold8)

1. 설정 → 휴대전화 정보 → 소프트웨어 정보 → **빌드번호 7번 탭** → 개발자 옵션 활성화.
2. 개발자 옵션 → **USB 디버깅** 켜기.
3. USB로 PC에 연결 → 기기에 뜨는 "USB 디버깅 허용"에서 허용.
4. PC에서 `adb devices` 로 기기가 `device` 상태인지 확인.

## 2. PC Firefox 연결

1. PC에 **데스크톱 Firefox** 를 설치합니다. 앱의 Gecko 버전은 `160` 입니다 — 데스크톱 Firefox도 **160 이상(가능하면 같은 메이저)** 을 쓰세요. 버전 차이가 크면 연결 시 경고가 뜨거나 실패할 수 있습니다.
2. 기기에서 **진단용 Adownloader 를 실행**해 둡니다(실행 중이어야 디버거 소켓이 열립니다).
3. PC Firefox 주소창에 `about:debugging` → 왼쪽 **설정(Setup)** → **USB 장치 사용(Enable USB Devices)**.
4. 왼쪽에 기기가 나타나면 **연결(Connect)** → 기기 항목을 클릭 → 진단용 앱의 탭 목록이 보입니다 → 해당 탭의 **검사(Inspect)**.

USB 자동 인식이 안 될 때(수동 연결):

```
adb shell cat /proc/net/unix | grep -a firefox-debugger
# 예: ... @com.adownloader.app.diag/firefox-debugger-socket
adb forward tcp:6000 localabstract:com.adownloader.app.diag/firefox-debugger-socket
```
→ `about:debugging` → 설정 → **네트워크 위치**에 `localhost:6000` 추가 → 연결.
(소켓 이름은 위 `grep` 결과에 나온 그대로, 앞의 `@` 만 빼고 사용합니다.)

## 3. 네트워크 기록 절차 (재생 **전에** 시작)

목적: "다운로드 순간에 새 요청이 없었다(버퍼/캐시)"는 가능성을 배제하고, 재생이 실제로 어떤 주소에서 무엇을 받는지 확정합니다.

1. 진단용 앱에서 영상 페이지를 열되 **아직 재생하지 않습니다.**
2. 그 탭의 개발자도구 → **네트워크(Network)** 패널:
   - **로그 유지(Persist Logs)** 켜기, **캐시 비활성화(Disable Cache)** 켜기(개발자도구가 열려 있는 동안만 적용됨), 목록 지우기.
3. **페이지 새로고침** → 이후 **재생** 시작.
4. 재생 바를 **아직 받지 않은 먼 위치(예: 70%)로 2~3번 탐색** → 새 세그먼트 요청을 강제로 발생시킵니다.
5. 필터창에 차례로 입력해 확인: `m3u8`, `.ts`, `m4s`, `jkyuilkgjh88788`, `yapsyas`.
6. 각 미디어 요청에서 기록할 것(아래 6절의 개인정보 규칙 준수):
   - **호스트**, 경로의 모양(예: `…/seg-00012.ts`, 쿼리 제외), **상태 코드**(200/206/403…), **유형(Type)**, **전송됨(Transferred)** 열 — "service worker"/"cached" 표시 여부
   - URL에 **서명 쿼리가 있는지 여부**(예/아니오만), 요청 헤더에 **Cookie 헤더가 있는지 여부**(예/아니오만)
   - **시작자(Initiator)** 가 어떤 스크립트/프레임인지
7. 플레이어가 **iframe 안**에 있으면, 개발자도구 상단의 프레임(컨텍스트) 선택에서 그 iframe을 고른 뒤 같은 확인을 합니다.

### 한 번에 확인하는 콘솔 진단 (개인정보 안전 — 호스트·개수·예/아니오만 출력)

개발자도구 **콘솔**에서(플레이어 iframe이 있으면 그 컨텍스트에서) 재생·탐색 후 붙여넣기:

```js
(() => {
  const videos = [...document.querySelectorAll('video')].map(e => ({
    mse: (e.currentSrc || '').startsWith('blob:'),   // MSE(자바스크립트가 세그먼트를 받아 주입)
    eme: !!e.mediaKeys,                               // EME = DRM 사용 여부
    playing: !e.paused && e.currentTime > 0,
    bufferedRanges: e.buffered.length
  }));
  const mediaHosts = {};
  for (const r of performance.getEntriesByType('resource')) {
    try {
      const u = new URL(r.name);
      if (/\.(m3u8|mpd|ts|m4s|mp4|aac)$/i.test(u.pathname))
        mediaHosts[u.host] = (mediaHosts[u.host] || 0) + 1;
    } catch (_) {}
  }
  return { videos, mediaHosts, serviceWorkerControlled: !!navigator.serviceWorker?.controller };
})()
```

## 4. ADownloader 쪽 비교 자료

같은 세션에서 진단용 앱으로 **다운로드를 한 번 시도**하고 실패 보고서를 내보냅니다. 보고서의 대상은 현재
`mediaHost: jkyuilkgjh88788.com`(매니페스트) → `failure.host: yapsyas.com`(세그먼트)입니다. 3절에서 본 실제 재생 주소와 비교합니다.

## 5. 결과 판정표

| 개발자도구에서 본 것 | 판정 | 다음 작업 |
|---|---|---|
| 재생 매니페스트·세그먼트가 **같은 호스트/경로**(jkyuilkgjh88788 → yapsyas)이고 세그먼트가 **200/206** | **같은 스트림**. 재생 탭 안의 요청은 허용, ADownloader의 탭 밖 요청은 403 | 요청 차이 비교(서명 쿼리 유무, Cookie 유무, Referer/Origin, `Sec-Fetch-*`). 앱이 놓친 재생 측 매니페스트 응답(`GECKO_CACHE_MISS`)을 정상 경로로 확보하는 방안 검토. 또한 WebExtension 관찰 누락(count 0)은 **앱 쪽 결함 후보** |
| 재생 매니페스트/세그먼트가 **다른 URL·호스트·화질** | **후보 불일치** — 앱이 DOM에서 고른 주소가 실제 재생 스트림이 아님 | 영상 후보 탐지·URL 해석 수정 |
| 요청은 보이나 **Transferred 가 service worker** 이거나 콘솔 `serviceWorkerControlled: true` | WebExtension 한계 가설: 서비스워커가 보낸 요청은 webRequest에서 `tabId = -1` 로 오고, 앱의 관찰 코드는 `tabId < 0` 을 버림 | **가설** — 확인되면 관찰 경로를 앱에서 보완 가능(정상 범위) |
| 재생 탭의 세그먼트 요청도 **403** | 서버가 재생 요청도 거부 — 영상은 다른 경로로 재생 중 | 해당 경로 추가 확인 |
| 캐시 비활성화 + 탐색해도 **미디어 요청이 전혀 없음** | Gecko 네트워크 모니터에도 안 보이는 재생 경로 | 콘솔 `eme: true` 면 **DRM(EME)** — 우회하지 않음. `eme: false` 면 재생 경로 추가 조사 |

- **WebExtension 한계 vs GeckoView 미디어 처리 경로 구분:** 개발자도구에는 보이는데 ADownloader 관찰이 0이면 → WebExtension(관찰 코드) 쪽 한계. 개발자도구에도 안 보이면 → GeckoView 미디어 처리 경로 쪽.
- DRM이나 서버의 명시적 다운로드 제한은 **우회하지 않습니다.** 판정표는 원인 분리를 위한 것입니다.

## 6. 공유할 때 개인정보 규칙

- **공유 금지:** 쿠키 값, `Authorization` 값, 서명 토큰·쿼리 문자열이 포함된 **전체 URL**, **HAR 파일 원본**(쿠키와 토큰이 그대로 들어 있습니다).
- **공유해도 되는 것:** 호스트명, 쿼리를 뺀 경로 모양, 상태 코드, 요청 유형, Transferred 표시, 헤더가 "있음/없음", 위 콘솔 진단 결과(호스트와 개수만 나옴), 스크린샷(주소창·쿠키가 가려진 상태).

## 7. 정리

- 검증 후 진단용 앱 삭제: `adb uninstall com.adownloader.app.diag`
- `adb forward --remove-all`, 개발자 옵션의 USB 디버깅 끄기.
