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
    private func navigate(_ index: Int) {
        let controls = app.buttons.matching(identifier: "navigate-\(index)")
        let ready = expectation(for: NSPredicate(format: "count == 1"), evaluatedWith: controls)
        wait(for: [ready], timeout: 30)
        controls.element.tap()
    }
    private func selectLanguage(_ language: String) {
        let selector = app.buttons["language-selector"]
        XCTAssertTrue(selector.waitForExistence(timeout: 10)); selector.tap()
        let option = app.descendants(matching: .any)["language-\(language)"]
        XCTAssertTrue(option.waitForExistence(timeout: 10))
        let ready = expectation(for: NSPredicate(format: "hittable == true"), evaluatedWith: option)
        wait(for: [ready], timeout: 10)
        option.tap()
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
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
    }
    func testOfflineNavigationAndSettingsPersistAcrossLaunch() throws {
        let settings = app.buttons["navigate-4"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30))
        navigate(4)
        XCTAssertTrue(app.descendants(matching: .any)["settings-category-general"].waitForExistence(timeout: 10))
        app.descendants(matching: .any)["settings-category-general"].tap()
        XCTAssertTrue(app.staticTexts["Language"].waitForExistence(timeout: 10))
        navigate(3)
        XCTAssertTrue(app.staticTexts["What would you like to do?"].waitForExistence(timeout: 10))
        navigate(1)
        navigate(2)
        navigate(4)
        app.descendants(matching: .any)["settings-category-general"].tap()
        selectLanguage("zh")
        XCTAssertTrue(app.staticTexts["语言"].waitForExistence(timeout: 10))
        app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["navigate-4"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.buttons["navigate-4"].label, "设置")
        navigate(4)
        app.descendants(matching: .any)["settings-category-general"].tap()
        selectLanguage("en")
        XCTAssertTrue(app.staticTexts["Language"].waitForExistence(timeout: 10))
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Landscape workbench"; attachment.lifetime = .keepAlways; add(attachment)
    }
}
