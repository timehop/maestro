/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.orchestra

import maestro.DeviceInfo
import maestro.ElementFilter
import maestro.Filters
import maestro.Filters.asFilter
import maestro.FindElementResult
import maestro.Maestro
import maestro.MaestroException
import maestro.js.Js
import maestro.js.JsEngine
import maestro.networkproxy.NetworkProxy
import maestro.networkproxy.yaml.YamlMappingRuleParser
import maestro.orchestra.error.UnicodeNotSupportedError
import maestro.orchestra.filter.FilterWithDescription
import maestro.orchestra.filter.TraitFilters
import maestro.orchestra.geo.Traveller
import maestro.orchestra.util.Env.evaluateScripts
import maestro.orchestra.yaml.YamlCommandReader
import maestro.toSwipeDirection
import maestro.utils.MaestroTimer
import maestro.utils.StringUtils.toRegexSafe
import java.io.File
import java.lang.Long.max
import java.nio.file.Files

class Orchestra(
    private val maestro: Maestro,
    private val stateDir: File? = null,
    private val screenshotsDir: File? = null,
    private val lookupTimeoutMs: Long = 17000L,
    private val optionalLookupTimeoutMs: Long = 7000L,
    private val networkProxy: NetworkProxy = NetworkProxy(port = 8085),
    private val onFlowStart: (List<MaestroCommand>) -> Unit = {},
    private val onCommandStart: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandComplete: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandFailed: (Int, MaestroCommand, Throwable) -> ErrorResolution = { _, _, e -> throw e },
    private val onCommandSkipped: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandReset: (MaestroCommand) -> Unit = {},
    private val onCommandMetadataUpdate: (MaestroCommand, CommandMetadata) -> Unit = { _, _ -> },
    private val jsEngine: JsEngine = JsEngine(),
) {

    private var copiedText: String? = null

    private var timeMsOfLastInteraction = System.currentTimeMillis()
    private var deviceInfo: DeviceInfo? = null

    private val rawCommandToMetadata = mutableMapOf<MaestroCommand, CommandMetadata>()

    /**
     * If initState is provided, initialize app disk state with the provided OrchestraAppState and skip
     * any initFlow execution. Otherwise, initialize app state with initFlow if defined.
     */
    fun runFlow(
        commands: List<MaestroCommand>,
        initState: OrchestraAppState? = null,
    ): Boolean {
        jsEngine.init()

        timeMsOfLastInteraction = System.currentTimeMillis()

        val config = YamlCommandReader.getConfig(commands)
        val state = initState ?: config?.initFlow?.let {
            runInitFlow(it) ?: return false
        }

        if (state != null) {
            maestro.clearAppState(state.appId)
            maestro.pushAppState(state.appId, state.file)
        }

        onFlowStart(commands)
        return executeCommands(commands, config)
    }

    /**
     * Run the initFlow and return the resulting app OrchestraAppState which can be used to initialize
     * app disk state when past into Orchestra.runFlow.
     */
    fun runInitFlow(
        initFlow: MaestroInitFlow,
    ): OrchestraAppState? {
        val success = runFlow(
            initFlow.commands,
            initState = null,
        )
        if (!success) return null

        maestro.stopApp(initFlow.appId)

        val stateFile = if (stateDir == null) {
            Files.createTempFile(null, ".state")
        } else {
            Files.createTempFile(stateDir.toPath(), null, ".state")
        }
        maestro.pullAppState(initFlow.appId, stateFile.toFile())

        return OrchestraAppState(
            appId = initFlow.appId,
            file = stateFile.toFile(),
        )
    }

    fun executeCommands(
        commands: List<MaestroCommand>,
        config: MaestroConfig? = null
    ): Boolean {
        jsEngine.init()

        commands
            .forEachIndexed { index, command ->
                onCommandStart(index, command)

                jsEngine.onLogMessage { msg ->
                    val metadata = getMetadata(command)
                    updateMetadata(
                        command,
                        metadata.copy(logMessages = metadata.logMessages + msg)
                    )
                }

                val evaluatedCommand = command.evaluateScripts(jsEngine)
                val metadata = getMetadata(command)
                    .copy(
                        evaluatedCommand = evaluatedCommand,
                    )
                updateMetadata(command, metadata)

                try {
                    executeCommand(evaluatedCommand, config)
                    onCommandComplete(index, command)
                } catch (ignored: CommandSkipped) {
                    // Swallow exception
                    onCommandSkipped(index, command)
                } catch (e: Throwable) {
                    when (onCommandFailed(index, command, e)) {
                        ErrorResolution.FAIL -> return false
                        ErrorResolution.CONTINUE -> {
                            // Do nothing
                        }
                    }
                }
            }
        return true
    }

    private fun executeCommand(maestroCommand: MaestroCommand, config: MaestroConfig?): Boolean {
        val command = maestroCommand.asCommand()

        return when (command) {
            is TapOnElementCommand -> {
                tapOnElement(
                    command,
                    command.retryIfNoChange ?: true,
                    command.waitUntilVisible ?: false,
                    config
                )
            }

            is TapOnPointCommand -> tapOnPoint(command, command.retryIfNoChange ?: true)
            is TapOnPointV2Command -> tapOnPointV2Command(command)
            is BackPressCommand -> backPressCommand()
            is HideKeyboardCommand -> hideKeyboardCommand()
            is ScrollCommand -> scrollVerticalCommand()
            is CopyTextFromCommand -> copyTextFromCommand(command)
            is ScrollUntilVisibleCommand -> scrollUntilVisible(command)
            is PasteTextCommand -> pasteText()
            is SwipeCommand -> swipeCommand(command)
            is AssertCommand -> assertCommand(command)
            is AssertConditionCommand -> assertConditionCommand(command)
            is InputTextCommand -> inputTextCommand(command)
            is InputRandomCommand -> inputTextRandomCommand(command)
            is LaunchAppCommand -> launchAppCommand(command)
            is OpenLinkCommand -> openLinkCommand(command, config)
            is PressKeyCommand -> pressKeyCommand(command)
            is EraseTextCommand -> eraseTextCommand(command)
            is TakeScreenshotCommand -> takeScreenshotCommand(command)
            is StopAppCommand -> stopAppCommand(command)
            is ClearStateCommand -> clearAppStateCommand(command)
            is ClearKeychainCommand -> clearKeychainCommand()
            is RunFlowCommand -> runFlowCommand(command, config)
            is SetLocationCommand -> setLocationCommand(command)
            is RepeatCommand -> repeatCommand(command, maestroCommand, config)
            is DefineVariablesCommand -> defineVariablesCommand(command)
            is RunScriptCommand -> runScriptCommand(command)
            is EvalScriptCommand -> evalScriptCommand(command)
            is ApplyConfigurationCommand -> false
            is WaitForAnimationToEndCommand -> waitForAnimationToEndCommand(command)
            is MockNetworkCommand -> mockNetworkCommand(command)
            is TravelCommand -> travelCommand(command)
            is AssertOutgoingRequestsCommand -> assertOutgoingRequestsCommand(command)
            else -> true
        }.also { mutating ->
            if (mutating) {
                timeMsOfLastInteraction = System.currentTimeMillis()
            }
        }
    }

    private fun assertOutgoingRequestsCommand(command: AssertOutgoingRequestsCommand): Boolean {
        val matched = maestro.assertOutgoingRequest(
            path = command.path,
            assertHeaderIsPresent = command.headersPresent,
            assertHttpMethod = command.httpMethodIs,
            assertRequestBodyContains = command.requestBodyContains,
            assertHeadersAndValues = command.headersAndValues,
        )

        if (!matched) throw MaestroException.OutgoingRequestAssertionFailure("Outgoing request assertion failed: ${command.description()}")
        return true
    }

    private fun travelCommand(command: TravelCommand): Boolean {
        Traveller.travel(
            maestro = maestro,
            points = command.points,
            speedMPS = command.speedMPS ?: 4.0,
        )

        return true
    }

    private fun assertConditionCommand(command: AssertConditionCommand): Boolean {
        val timeout = (command.timeoutMs() ?: lookupTimeoutMs)
        if (!evaluateCondition(command.condition, timeoutMs = timeout)) {
            if (!isOptional(command.condition)) {
                throw MaestroException.AssertionFailure(
                    "Assertion is false: ${command.condition.description()}",
                    maestro.viewHierarchy().root,
                )
            } else {
                throw CommandSkipped
            }
        }

        return false
    }

    private fun isOptional(condition: Condition): Boolean {
        return condition.visible?.optional == true
            || condition.notVisible?.optional == true
    }

    private fun evalScriptCommand(command: EvalScriptCommand): Boolean {
        command.scriptString.evaluateScripts(jsEngine)

        // We do not actually know if there were any mutations, but we assume there were
        return true
    }

    private fun runScriptCommand(command: RunScriptCommand): Boolean {
        jsEngine.evaluateScript(
            script = command.script,
            env = command.env,
            sourceName = command.sourceDescription,
            runInSubScope = true,
        )

        // We do not actually know if there were any mutations, but we assume there were
        return true
    }

    private fun waitForAnimationToEndCommand(command: WaitForAnimationToEndCommand): Boolean {
        maestro.waitForAnimationToEnd(command.timeout)

        return true
    }

    private fun defineVariablesCommand(command: DefineVariablesCommand): Boolean {
        command.env.forEach { (name, value) ->
            val cleanValue = Js.sanitizeJs(value)
            jsEngine.evaluateScript("var $name = '$cleanValue'")
        }

        return false
    }

    private fun setLocationCommand(command: SetLocationCommand): Boolean {
        maestro.setLocation(command.latitude, command.longitude)

        return true
    }

    private fun clearAppStateCommand(command: ClearStateCommand): Boolean {
        maestro.clearAppState(command.appId)
        // Android's clear command also resets permissions
        // Reset all permissions to unset so both platforms behave the same
        maestro.setPermissions(command.appId, mapOf("all" to "unset"))

        return true
    }

    private fun stopAppCommand(command: StopAppCommand): Boolean {
        maestro.stopApp(command.appId)

        return true
    }

    private fun scrollVerticalCommand(): Boolean {
        maestro.scrollVertical()
        return true
    }

    private fun scrollUntilVisible(command: ScrollUntilVisibleCommand): Boolean {
        val endTime = System.currentTimeMillis() + command.timeout
        val direction = command.direction.toSwipeDirection()
        val deviceInfo = maestro.deviceInfo()
        do {
            try {
                val element = findElement(command.selector, 500).element
                val visibility = element.getVisiblePercentage(deviceInfo.widthGrid, deviceInfo.heightGrid)
                if (visibility >= command.visibilityPercentageNormalized) {
                    return true
                }

            } catch (ignored: MaestroException.ElementNotFound) {
            }
            maestro.swipeFromCenter(direction, durationMs = command.scrollDuration)
        } while (System.currentTimeMillis() < endTime)

        throw MaestroException.ElementNotFound("No visible element found: ${command.selector.description()}", maestro.viewHierarchy().root)
    }

    private fun hideKeyboardCommand(): Boolean {
        maestro.hideKeyboard()
        return true
    }

    private fun backPressCommand(): Boolean {
        maestro.backPress()
        return true
    }

    private fun mockNetworkCommand(command: MockNetworkCommand): Boolean {
        maestro.setProxy(
            port = networkProxy.port,
        )

        val rules = YamlMappingRuleParser.readRules(File(command.path).toPath())
        if (!networkProxy.isStarted()) {
            networkProxy.start(rules)
        } else {
            networkProxy.replaceRules(rules)
        }

        return false
    }

    private fun repeatCommand(command: RepeatCommand, maestroCommand: MaestroCommand, config: MaestroConfig?): Boolean {
        val maxRuns = command.times?.toDoubleOrNull()?.toInt() ?: Int.MAX_VALUE

        var counter = 0
        var metadata = getMetadata(maestroCommand)
        metadata = metadata.copy(
            numberOfRuns = 0,
        )

        var mutatiing = false

        val checkCondition: () -> Boolean = {
            command.condition
                ?.evaluateScripts(jsEngine)
                ?.let { evaluateCondition(it) } != false
        }

        while (checkCondition() && counter < maxRuns) {
            if (counter > 0) {
                command.commands.forEach { resetCommand(it) }
            }

            val mutated = runSubFlow(command.commands, config)
            mutatiing = mutatiing || mutated
            counter++

            metadata = metadata.copy(
                numberOfRuns = counter,
            )
            updateMetadata(maestroCommand, metadata)
        }

        if (counter == 0) {
            throw CommandSkipped
        }

        return mutatiing
    }

    private fun updateMetadata(rawCommand: MaestroCommand, metadata: CommandMetadata) {
        rawCommandToMetadata[rawCommand] = metadata
        onCommandMetadataUpdate(rawCommand, metadata)
    }

    private fun getMetadata(rawCommand: MaestroCommand) = rawCommandToMetadata.getOrPut(rawCommand) {
        CommandMetadata()
    }

    private fun resetCommand(command: MaestroCommand) {
        onCommandReset(command)

        (command.asCommand() as? CompositeCommand)?.let {
            it.subCommands().forEach { command ->
                resetCommand(command)
            }
        }
    }

    private fun runFlowCommand(command: RunFlowCommand, config: MaestroConfig?): Boolean {
        return if (evaluateCondition(command.condition)) {
            runSubFlow(command.commands, config)
        } else {
            throw CommandSkipped
        }
    }

    private fun evaluateCondition(
        condition: Condition?,
        timeoutMs: Long? = null,
    ): Boolean {
        if (condition == null) {
            return true
        }

        condition.platform?.let {
            if (it != maestro.deviceInfo().platform) {
                return false
            }
        }

        condition.visible?.let {
            try {
                findElement(it, timeoutMs = adjustedToLatestInteraction(timeoutMs ?: optionalLookupTimeoutMs))
            } catch (ignored: MaestroException.ElementNotFound) {
                return false
            }
        }

        condition.notVisible?.let {
            val result = MaestroTimer.withTimeout(adjustedToLatestInteraction(timeoutMs ?: optionalLookupTimeoutMs)) {
                try {
                    findElement(it, timeoutMs = 500L)

                    // If we got to that point, the element is still visible.
                    // Returning null to keep waiting.
                    null
                } catch (ignored: MaestroException.ElementNotFound) {
                    // Element was not visible, as we expected
                    true
                }
            }

            // Element was actually visible
            if (result != true) {
                return false
            }
        }

        condition.scriptCondition?.let { value ->
            // Note that script should have been already evaluated by this point

            if (value.isBlank()) {
                return false
            }

            if (value.equals("false", ignoreCase = true)) {
                return false
            }

            if (value == "undefined") {
                return false
            }

            if (value == "null") {
                return false
            }

            if (value.toDoubleOrNull() == 0.0) {
                return false
            }
        }

        return true
    }

    private fun runSubFlow(commands: List<MaestroCommand>, config: MaestroConfig?): Boolean {
        jsEngine.enterScope()

        return try {
            commands
                .mapIndexed { index, command ->
                    onCommandStart(index, command)

                    val evaluatedCommand = command.evaluateScripts(jsEngine)
                    val metadata = getMetadata(command)
                        .copy(
                            evaluatedCommand = evaluatedCommand,
                        )
                    updateMetadata(command, metadata)

                    return@mapIndexed try {
                        executeCommand(evaluatedCommand, config)
                            .also {
                                onCommandComplete(index, command)
                            }
                    } catch (ignored: CommandSkipped) {
                        // Swallow exception
                        onCommandSkipped(index, command)
                        false
                    } catch (e: Throwable) {
                        when (onCommandFailed(index, command, e)) {
                            ErrorResolution.FAIL -> throw e
                            ErrorResolution.CONTINUE -> {
                                // Do nothing
                                false
                            }
                        }
                    }
                }
                .any { it }
        } finally {
            jsEngine.leaveScope()
        }
    }

    private fun takeScreenshotCommand(command: TakeScreenshotCommand): Boolean {
        val pathStr = command.path + ".png"
        val file = screenshotsDir
            ?.let { File(it, pathStr) }
            ?: File(pathStr)

        maestro.takeScreenshot(file)

        return false
    }

    private fun eraseTextCommand(command: EraseTextCommand): Boolean {
        val charactersToErase = command.charactersToErase
        maestro.eraseText(charactersToErase ?: MAX_ERASE_CHARACTERS)
        maestro.waitForAppToSettle()

        return true
    }

    private fun pressKeyCommand(command: PressKeyCommand): Boolean {
        maestro.pressKey(command.code)

        return true
    }

    private fun openLinkCommand(command: OpenLinkCommand, config: MaestroConfig?): Boolean {
        maestro.openLink(command.link, config?.appId, command.autoVerify ?: false, command.browser ?: false)

        return true
    }

    private fun launchAppCommand(command: LaunchAppCommand): Boolean {
        try {
            if (command.clearKeychain == true) {
                maestro.clearKeychain()
            }
            if (command.clearState == true) {
                maestro.clearAppState(command.appId)
            }

            // For testing convenience, default to allow all on app launch
            val permissions = command.permissions ?: mapOf("all" to "allow")
            maestro.setPermissions(command.appId, permissions)

        } catch (e: Exception) {
            throw MaestroException.UnableToClearState("Unable to clear state for app ${command.appId}")
        }

        try {
            maestro.launchApp(
                appId = command.appId,
                launchArguments = command.launchArguments ?: emptyMap(),
                stopIfRunning = command.stopApp ?: true
            )
        } catch (e: Exception) {
            throw MaestroException.UnableToLaunchApp("Unable to launch app ${command.appId}: ${e.message}")
        }

        return true
    }

    private fun clearKeychainCommand(): Boolean {
        maestro.clearKeychain()

        return true
    }

    private fun inputTextCommand(command: InputTextCommand): Boolean {
        if (!maestro.isUnicodeInputSupported()) {
            val isAscii = Charsets.US_ASCII.newEncoder()
                .canEncode(command.text)

            if (!isAscii) {
                throw UnicodeNotSupportedError(command.text)
            }
        }

        maestro.inputText(command.text)

        return true
    }

    private fun inputTextRandomCommand(command: InputRandomCommand): Boolean {
        inputTextCommand(InputTextCommand(text = command.genRandomString()))

        return true
    }

    private fun assertCommand(command: AssertCommand): Boolean {
        return assertConditionCommand(
            command.toAssertConditionCommand()
        )
    }

    private fun tapOnElement(
        command: TapOnElementCommand,
        retryIfNoChange: Boolean,
        waitUntilVisible: Boolean,
        config: MaestroConfig?,
    ): Boolean {
        return try {
            val result = findElement(command.selector)
            maestro.tap(
                result.element,
                result.hierarchy,
                retryIfNoChange,
                waitUntilVisible,
                command.longPress ?: false,
                config?.appId
            )

            true
        } catch (e: MaestroException.ElementNotFound) {
            if (!command.selector.optional) {
                throw e
            } else {
                false
            }
        }
    }

    private fun tapOnPoint(
        command: TapOnPointCommand,
        retryIfNoChange: Boolean,
    ): Boolean {
        maestro.tap(
            command.x,
            command.y,
            retryIfNoChange,
            command.longPress ?: false,
        )

        return true
    }

    private fun tapOnPointV2Command(
        command: TapOnPointV2Command,
    ): Boolean {
        val point = command.point

        if (point.contains("%")) {
            val (percentX, percentY) = point
                .replace("%", "")
                .split(",")
                .map { it.trim().toInt() }

            if (percentX !in 0..100 || percentY !in 0..100) {
                throw MaestroException.InvalidCommand("Invalid point: $point")
            }

            maestro.tapOnRelative(
                percentX = percentX,
                percentY = percentY,
                retryIfNoChange = command.retryIfNoChange ?: true,
                longPress = command.longPress ?: false
            )
        } else {
            val (x, y) = point.split(",")
                .map {
                    it.trim().toInt()
                }

            maestro.tap(
                x = x,
                y = y,
                retryIfNoChange = command.retryIfNoChange ?: true,
                longPress = command.longPress ?: false
            )
        }

        return true
    }

    private fun findElement(
        selector: ElementSelector,
        timeoutMs: Long? = null
    ): FindElementResult {
        val timeout = timeoutMs
            ?: adjustedToLatestInteraction(
                if (selector.optional) {
                    optionalLookupTimeoutMs
                } else {
                    lookupTimeoutMs
                }
            )

        val (description, filterFunc) = buildFilter(
            selector,
            deviceInfo(),
        )

        return maestro.findElementWithTimeout(
            timeoutMs = timeout,
            filter = filterFunc,
        ) ?: throw MaestroException.ElementNotFound(
            "Element not found: $description",
            maestro.viewHierarchy().root,
        )
    }

    private fun deviceInfo() = deviceInfo
        ?: maestro.deviceInfo().also { deviceInfo = it }

    private fun buildFilter(
        selector: ElementSelector,
        deviceInfo: DeviceInfo,
    ): FilterWithDescription {
        val filters = mutableListOf<ElementFilter>()
        val descriptions = mutableListOf<String>()

        selector.textRegex
            ?.let {
                descriptions += "Text matching regex: $it"
                filters += Filters.deepestMatchingElement(
                    Filters.textMatches(it.toRegexSafe(REGEX_OPTIONS))
                )
            }

        selector.idRegex
            ?.let {
                descriptions += "Id matching regex: $it"
                filters += Filters.deepestMatchingElement(
                    Filters.idMatches(it.toRegexSafe(REGEX_OPTIONS))
                )
            }

        selector.size
            ?.let {
                descriptions += "Size: $it"
                filters += Filters.sizeMatches(
                    width = it.width,
                    height = it.height,
                    tolerance = it.tolerance,
                ).asFilter()
            }

        selector.below
            ?.let {
                descriptions += "Below: ${it.description()}"
                filters += Filters.below(buildFilter(it, deviceInfo).filterFunc)
            }

        selector.above
            ?.let {
                descriptions += "Above: ${it.description()}"
                filters += Filters.above(buildFilter(it, deviceInfo).filterFunc)
            }

        selector.leftOf
            ?.let {
                descriptions += "Left of: ${it.description()}"
                filters += Filters.leftOf(buildFilter(it, deviceInfo).filterFunc)
            }

        selector.rightOf
            ?.let {
                descriptions += "Right of: ${it.description()}"
                filters += Filters.rightOf(buildFilter(it, deviceInfo).filterFunc)
            }

        selector.containsChild
            ?.let {
                descriptions += "Contains child: ${it.description()}"
                filters += Filters.containsChild(findElement(it).element).asFilter()
            }

        selector.containsDescendants
            ?.let { descendantSelectors ->
                val descendantDescriptions = descendantSelectors.joinToString("; ") { it.description() }
                descriptions += "Contains descendants: $descendantDescriptions"
                filters += Filters.containsDescendants(descendantSelectors.map { buildFilter(it, deviceInfo).filterFunc })
            }

        selector.traits
            ?.map {
                TraitFilters.buildFilter(it)
            }
            ?.forEach { (description, filter) ->
                descriptions += description
                filters += filter
            }

        selector.enabled
            ?.let {
                descriptions += if (it) {
                    "Enabled"
                } else {
                    "Disabled"
                }
                filters += Filters.enabled(it)
            }

        selector.selected
            ?.let {
                descriptions += if (it) {
                    "Selected"
                } else {
                    "Not selected"
                }
                filters += Filters.selected(it)
            }

        selector.checked
            ?.let {
                descriptions += if (it) {
                    "Checked"
                } else {
                    "Not checked"
                }
                filters += Filters.checked(it)
            }

        selector.focused
            ?.let {
                descriptions += if (it) {
                    "Focused"
                } else {
                    "Not focused"
                }
                filters += Filters.focused(it)
            }

        var resultFilter = Filters.intersect(filters)
        resultFilter = selector.index
            ?.toDouble()
            ?.toInt()
            ?.let {
                Filters.compose(
                    resultFilter,
                    Filters.index(it)
                )
            } ?: Filters.compose(
            resultFilter,
            Filters.clickableFirst()
        )

        return FilterWithDescription(
            descriptions.joinToString(", "),
            resultFilter,
        )
    }

    private fun swipeCommand(command: SwipeCommand): Boolean {
        val elementSelector = command.elementSelector
        val direction = command.direction
        val startRelative = command.startRelative
        val endRelative = command.endRelative
        val start = command.startPoint
        val end = command.endPoint
        when {
            elementSelector != null && direction != null -> {
                val uiElement = findElement(elementSelector)
                maestro.swipe(direction, uiElement.element, command.duration)
            }

            startRelative != null && endRelative != null -> {
                maestro.swipe(startRelative = startRelative, endRelative = endRelative, duration = command.duration)
            }

            direction != null -> maestro.swipe(swipeDirection = direction, duration = command.duration)
            start != null && end != null -> maestro.swipe(startPoint = start, endPoint = end, duration = command.duration)
            else -> error("Illegal arguments for swiping")
        }
        return true
    }

    private fun adjustedToLatestInteraction(timeMs: Long) = max(
        0,
        timeMs - (System.currentTimeMillis() - timeMsOfLastInteraction)
    )

    private fun copyTextFromCommand(command: CopyTextFromCommand): Boolean {
        val result = findElement(command.selector)
        copiedText = resolveText(result.element.treeNode.attributes)
            ?: throw MaestroException.UnableToCopyTextFromElement("Element does not contain text to copy: ${result.element}")

        jsEngine.evaluateScript("maestro.copiedText = '${Js.sanitizeJs(copiedText ?: "")}'")

        return true
    }

    private fun resolveText(attributes: MutableMap<String, String>): String? {
        return if (!attributes["text"].isNullOrEmpty()) {
            attributes["text"]
        } else if(!attributes["hintText"].isNullOrEmpty()) {
            attributes["hintText"]
        } else {
            attributes["accessibilityText"]
        }
    }

    private fun pasteText(): Boolean {
        copiedText?.let { maestro.inputText(it) }
        return true
    }

    private object CommandSkipped : Exception()

    data class CommandMetadata(
        val numberOfRuns: Int? = null,
        val evaluatedCommand: MaestroCommand? = null,
        val logMessages: List<String> = emptyList()
    )

    enum class ErrorResolution {
        CONTINUE,
        FAIL
    }

    companion object {

        val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

        private const val MAX_ERASE_CHARACTERS = 50
        private const val MAX_LAUNCH_ARGUMENT_PAIRS_ALLOWED = 1
    }
}

data class OrchestraAppState(
    val appId: String,
    val file: File,
)
