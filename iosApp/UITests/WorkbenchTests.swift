import XCTest

final class WorkbenchTests: XCTestCase {
    private var app: XCUIApplication!
    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
    }
    override func tearDownWithError() throws {
        if (testRun?.totalFailureCount ?? 0) > 0 {
            let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            screenshot.name = "Failure screen"; screenshot.lifetime = .keepAlways; add(screenshot)
            let hierarchy = XCTAttachment(string: app.debugDescription)
            hierarchy.name = "Failure accessibility hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        }
    }
    private func tapWhenStable(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 10))
        var previousFrame = CGRect.null
        var stableSince = ProcessInfo.processInfo.systemUptime
        let ready = expectation(for: NSPredicate { _, _ in
            guard let snapshot = try? element.snapshot() else { return false }
            let frame = snapshot.frame
            guard snapshot.isEnabled && !frame.isEmpty &&
                frame.origin.x.isFinite && frame.origin.y.isFinite &&
                frame.width.isFinite && frame.height.isFinite else {
                previousFrame = .null
                return false
            }
            if frame != previousFrame {
                previousFrame = frame
                stableSince = ProcessInfo.processInfo.systemUptime
                return false
            }
            return ProcessInfo.processInfo.systemUptime - stableSince >= 0.35 && element.isHittable
        }, evaluatedWith: element)
        wait(for: [ready], timeout: 30)
        element.tap()
    }
    private func navigate(_ index: Int) {
        let controls = app.buttons.matching(identifier: "navigate-\(index)")
        let ready = expectation(for: NSPredicate(format: "count == 1"), evaluatedWith: controls)
        wait(for: [ready], timeout: 30)
        tapWhenStable(controls.element)
    }
    private func selectLanguage(_ language: String) {
        let selector = app.buttons["language-selector"]
        tapWhenStable(selector)
        let option = app.descendants(matching: .any)["language-\(language)"]
        XCTAssertTrue(option.waitForExistence(timeout: 10))
        tapWhenStable(option)
    }
    private func assertInputVisibleWithKeyboard(_ input: XCUIElement) {
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
        let keyboard = app.keyboards.firstMatch
        let aboveKeyboard = expectation(for: NSPredicate { _, _ in
            !keyboard.frame.isEmpty && self.app.frame.contains(input.frame) &&
                input.frame.maxY <= keyboard.frame.minY + 2
        }, evaluatedWith: app)
        wait(for: [aboveKeyboard], timeout: 10)
        XCTAssertTrue(app.frame.contains(input.frame))
        XCTAssertLessThanOrEqual(input.frame.maxY, app.keyboards.firstMatch.frame.minY + 2)
        XCTAssertGreaterThanOrEqual(input.frame.height, 48)
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
    }
    private func attachScreen(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testChatComposerRemainsVisibleWithLandscapeKeyboard() {
        let chat = app.buttons["navigate-3"]
        XCTAssertTrue(chat.waitForExistence(timeout: 30)); navigate(3)
        let input = app.descendants(matching: .any)["chat-input"]
        XCTAssertTrue(input.waitForExistence(timeout: 10)); input.tap(); input.typeText("Review before sending")
        assertInputVisibleWithKeyboard(input)
        XCTAssertEqual(input.value as? String, "Review before sending")
        XCTAssertTrue(app.buttons["Send"].isHittable)
        attachScreen("Landscape keyboard")
    }
    func testSettingsAndScriptInputsRemainVisibleWithLandscapeKeyboard() throws {
        navigate(4)
        tapWhenStable(app.descendants(matching: .any)["settings-category-model"])
        let endpoint = app.descendants(matching: .any)["model-endpoint"]
        XCTAssertTrue(endpoint.waitForExistence(timeout: 10))
        let originalEndpoint = try XCTUnwrap(endpoint.value as? String)
        endpoint.tap(); endpoint.typeText("hanppieuitest")
        assertInputVisibleWithKeyboard(endpoint)
        let endpointValue = try XCTUnwrap(endpoint.value as? String)
        XCTAssertTrue(endpointValue.contains("hanppieuitest"))
        XCTAssertEqual(endpointValue.replacingOccurrences(of: "hanppieuitest", with: ""), originalEndpoint)
        let form = app.descendants(matching: .any)["settings-detail-model"]
        XCTAssertGreaterThanOrEqual(form.frame.height, 48)
        XCTAssertTrue(form.frame.height.isFinite)
        XCTAssertTrue(app.buttons["settings-save"].isHittable)
        attachScreen("Landscape model settings keyboard")
        navigate(1)
        tapWhenStable(app.buttons["script-new"])
        let editor = app.descendants(matching: .any)["script-editor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 10))
        let originalSource = try XCTUnwrap(editor.value as? String)
        editor.tap(); editor.typeText("print(1)")
        assertInputVisibleWithKeyboard(editor)
        let editedSource = try XCTUnwrap(editor.value as? String)
        XCTAssertTrue(editedSource.contains("print(1)"))
        XCTAssertEqual(editedSource.replacingOccurrences(of: "print(1)", with: ""), originalSource)
        let viewport = app.descendants(matching: .any)["script-editor-viewport"]
        XCTAssertGreaterThanOrEqual(viewport.frame.height, 48)
        XCTAssertTrue(viewport.frame.height.isFinite)
        XCTAssertTrue(app.buttons["script-save"].isHittable)
        attachScreen("Landscape script editor keyboard")
    }
    func testOfflineNavigationAndSettingsPersistAcrossLaunch() throws {
        let settings = app.buttons["navigate-4"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30))
        navigate(4)
        XCTAssertTrue(app.descendants(matching: .any)["settings-category-general"].waitForExistence(timeout: 10))
        tapWhenStable(app.descendants(matching: .any)["settings-category-general"])
        XCTAssertTrue(app.staticTexts["Language"].waitForExistence(timeout: 10))
        navigate(3)
        XCTAssertTrue(app.staticTexts["What would you like to do?"].waitForExistence(timeout: 10))
        navigate(1)
        navigate(2)
        navigate(4)
        tapWhenStable(app.descendants(matching: .any)["settings-category-general"])
        selectLanguage("zh")
        XCTAssertTrue(app.staticTexts["语言"].waitForExistence(timeout: 10))
        app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["navigate-4"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.buttons["navigate-4"].label, "设置")
        navigate(4)
        tapWhenStable(app.descendants(matching: .any)["settings-category-general"])
        selectLanguage("en")
        XCTAssertTrue(app.staticTexts["Language"].waitForExistence(timeout: 10))
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
        attachScreen("Landscape workbench")
    }
}
