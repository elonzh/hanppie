import XCTest

final class WorkbenchTests: XCTestCase {
    private var app: XCUIApplication!
    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
        app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
    }
    override func tearDownWithError() throws {
        if (testRun?.totalFailureCount ?? 0) > 0 {
            let screenshot = XCTAttachment(screenshot: app.screenshot())
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
            guard element.exists && element.isHittable else { return false }
            let frame = element.frame
            if frame != previousFrame {
                previousFrame = frame
                stableSince = ProcessInfo.processInfo.systemUptime
                return false
            }
            return ProcessInfo.processInfo.systemUptime - stableSince >= 0.35
        }, evaluatedWith: element)
        wait(for: [ready], timeout: 10)
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
    func testChatComposerRemainsVisibleWithLandscapeKeyboard() {
        let chat = app.buttons["navigate-3"]
        XCTAssertTrue(chat.waitForExistence(timeout: 30)); navigate(3)
        let input = app.descendants(matching: .any)["chat-input"]
        XCTAssertTrue(input.waitForExistence(timeout: 10)); input.tap(); input.typeText("Review before sending")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
        let keyboard = app.keyboards.firstMatch
        let aboveKeyboard = expectation(for: NSPredicate { _, _ in
            !keyboard.frame.isEmpty && input.frame.maxY <= keyboard.frame.minY + 2
        }, evaluatedWith: app)
        wait(for: [aboveKeyboard], timeout: 10)
        XCTAssertLessThanOrEqual(input.frame.maxY, app.keyboards.firstMatch.frame.minY + 2)
        XCTAssertEqual(input.value as? String, "Review before sending")
        XCTAssertTrue(app.buttons["Send"].isHittable)
        XCTAssertGreaterThanOrEqual(input.frame.height, 48)
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
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
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Landscape workbench"; attachment.lifetime = .keepAlways; add(attachment)
    }
}
