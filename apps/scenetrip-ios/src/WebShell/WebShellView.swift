import SwiftUI
import UIKit
import WebKit

/// 웹뷰 껍데기 화면 (MZ2AZ-557, 계획 docs/project/plans/ios-webview-shell.md §2-2).
///
/// 빌드 스위치(`--define=scenetrip_web_shell_url`)가 켜졌을 때만 `SceneTripApp` 이 띄운다. 끄면 이 파일은 불리지 않는다.
/// 판단은 전부 `WebShellRules` 에 있고 여기는 WebKit·UIKit 배선만 한다.
struct WebShellView: UIViewControllerRepresentable {
    let base: URL

    func makeUIViewController(context _: Context) -> WebShellController {
        WebShellController(base: base)
    }

    func updateUIViewController(_: WebShellController, context _: Context) {}
}

final class WebShellController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    private let base: URL
    private var webView: WKWebView!
    private let spinner = UIActivityIndicatorView(style: .large)
    private let failure = UIStackView()
    private var loadedOnce = false

    init(base: URL) {
        self.base = base
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        fatalError("not used")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .white

        let configuration = WKWebViewConfiguration()
        configuration.applicationNameForUserAgent = "Mobile/15E148 Safari/604.1 \(WebShellRules.userAgentToken)"
        configuration.allowsInlineMediaPlayback = true
        // 로그인 다리 — 웹의 nativeAuth.ts 가 `window.webkit.messageHandlers.scenetripAuth` 로 부른다. 응답은 Promise 로 돌아간다.
        configuration.userContentController.addScriptMessageHandler(
            WebShellAuthBridge(), contentWorld: .page, name: WebShellRules.handlerName
        )

        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        // 아래 안전 영역은 웹이 viewport-fit=cover + env(safe-area-inset-bottom) 으로 맞춘다(떠 있는 탭바).
        // 위는 웹 홈 머리가 상태 막대를 비켜 주지 않아(2026-11-12 시뮬레이터) 웹뷰를 상태 막대 아래에서 시작한다 —
        // 그러면 웹의 env(safe-area-inset-top) 은 0 이 되어 두 번 밀리지 않는다.
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.bounces = false
        #if DEBUG
            if #available(iOS 16.4, *) {
                webView.isInspectable = true
            }
        #endif
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])

        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.hidesWhenStopped = true
        view.addSubview(spinner)
        NSLayoutConstraint.activate([
            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
        buildFailureView()
        load()
    }

    private func buildFailureView() {
        let label = UILabel()
        label.text = "연결할 수 없어요\nCan't connect"
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .secondaryLabel
        var config = UIButton.Configuration.filled()
        config.title = "다시 시도 · Retry"
        config.baseBackgroundColor = UIColor(red: 0x7B / 255, green: 0x69 / 255, blue: 0xED / 255, alpha: 1)
        let retry = UIButton(configuration: config, primaryAction: UIAction { [weak self] _ in self?.load() })
        failure.axis = .vertical
        failure.spacing = 16
        failure.alignment = .center
        failure.addArrangedSubview(label)
        failure.addArrangedSubview(retry)
        failure.isHidden = true
        failure.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(failure)
        NSLayoutConstraint.activate([
            failure.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            failure.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    private func load() {
        failure.isHidden = true
        spinner.startAnimating()
        if loadedOnce, webView.url != nil {
            webView.reload()
        } else {
            webView.load(URLRequest(url: base))
        }
    }

    private func openOutside(_ url: URL) {
        UIApplication.shared.open(url)
    }

    // MARK: WKNavigationDelegate

    func webView(_: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void)
    {
        let isMainFrame = action.targetFrame?.isMainFrame ?? true
        switch WebShellRules.navigation(url: action.request.url, isMainFrame: isMainFrame, base: base) {
        case .allow:
            decisionHandler(.allow)
        case .openExternally:
            if let url = action.request.url {
                openOutside(url)
            }
            decisionHandler(.cancel)
        case .cancel:
            decisionHandler(.cancel)
        }
    }

    func webView(_: WKWebView, didFinish _: WKNavigation!) {
        loadedOnce = true
        spinner.stopAnimating()
    }

    func webView(_: WKWebView, didFail _: WKNavigation!, withError error: Error) {
        showFailure(error)
    }

    func webView(_: WKWebView, didFailProvisionalNavigation _: WKNavigation!, withError error: Error) {
        showFailure(error)
    }

    /// 웹 콘텐츠 프로세스가 죽으면(메모리) 흰 화면이 남는다 — 다시 연다.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        webView.reload()
    }

    private func showFailure(_ error: Error) {
        spinner.stopAnimating()
        // 바깥으로 넘긴 이동(취소)은 실패가 아니다.
        if (error as NSError).code == NSURLErrorCancelled {
            return
        }
        if (error as NSError).domain == "WebKitErrorDomain", (error as NSError).code == 102 {
            return
        }
        if !loadedOnce {
            failure.isHidden = false
        }
    }

    // MARK: WKUIDelegate

    /// `target=_blank`·`window.open` — 같은 출처는 본 창에서, 나머지는 바깥으로. 새 웹뷰는 만들지 않는다.
    func webView(_ webView: WKWebView, createWebViewWith _: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures _: WKWindowFeatures) -> WKWebView?
    {
        switch WebShellRules.newWindow(url: action.request.url, base: base) {
        case .loadInPlace:
            webView.load(action.request)
        case .openExternally:
            if let url = action.request.url {
                openOutside(url)
            }
        case .ignore:
            break
        }
        return nil
    }

    func webView(_: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame _: WKFrameInfo, completionHandler: @escaping @MainActor () -> Void)
    {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(alert, animated: true)
    }

    func webView(_: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame _: WKFrameInfo, completionHandler: @escaping @MainActor (Bool) -> Void)
    {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(alert, animated: true)
    }
}

/// 로그인 다리 — 공급자 단계(구글 GIDSignIn · Apple ASAuthorization)만 네이티브가 하고 자격을 웹에 돌려준다.
/// 서버 교환·토큰 저장은 웹이 한다(계획 §2-3, ADR 0029). 토큰은 메시지 본문으로만 오가고 URL 에 실리지 않는다.
///
/// 메시지 처리기는 컨트롤러와 따로 둔다 — `WKUserContentController` 가 처리기를 강하게 잡아 순환 참조가 생기지 않게.
final class WebShellAuthBridge: NSObject, WKScriptMessageHandlerWithReply {
    func userContentController(_: WKUserContentController, didReceive message: WKScriptMessage,
                               replyHandler: @escaping @MainActor (Any?, String?) -> Void)
    {
        guard let provider = WebShellRules.authProvider(from: message.body) else {
            replyHandler(nil, WebShellRules.failedMessage)
            return
        }
        Task { @MainActor in
            switch provider {
            case .google:
                do {
                    let result = try await GoogleOAuth.signIn()
                    replyHandler(WebShellRules.googleReply(idToken: result.idToken, nonce: result.nonce), nil)
                } catch GoogleOAuth.Failure.cancelled {
                    replyHandler(nil, WebShellRules.cancelledMessage)
                } catch {
                    replyHandler(nil, WebShellRules.failedMessage)
                }
            case .apple:
                do {
                    let body = try await AppleOAuth.signIn()
                    replyHandler(WebShellRules.appleReply(
                        identityToken: body.identityToken, authorizationCode: body.authorizationCode, nonce: body.nonce,
                        givenName: body.givenName, familyName: body.familyName
                    ), nil)
                } catch AppleOAuth.Failure.cancelled {
                    replyHandler(nil, WebShellRules.cancelledMessage)
                } catch {
                    replyHandler(nil, WebShellRules.failedMessage)
                }
            }
        }
    }
}
