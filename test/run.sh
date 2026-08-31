#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21}
export PATH="$HOME/.opencode/bin:$PATH"
gson=${GSON:-$HOME/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar}
# `|| true`: with `set -o pipefail`, an unmatched glob would otherwise abort the whole run
# before the first test instead of taking the documented "SWT unavailable" path below.
swt_api=${SWT_API:-$(ls /usr/share/dbeaver-*/plugins/org.eclipse.swt_[0-9]*.jar 2>/dev/null | head -1 || true)}
swt_gtk=${SWT_GTK:-$(ls /usr/share/dbeaver-*/plugins/org.eclipse.swt.gtk.linux.x86_64_*.jar 2>/dev/null | head -1 || true)}

echo "== Tycho build =="
(cd "$root" && ./mvnw -q -B verify)

echo "== pure renderer tests =="
javac -d "$out" -cp "$gson" \
  "$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/MarkdownHtml.java" \
  "$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/ConversationHtml.java" \
	"$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/Events.java" \
	"$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/ModelChoice.java" \
	"$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/ProviderAuthPrompts.java" \
	"$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/WorkspaceRoot.java" \
	"$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/AttachmentSelection.java" \
	"$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/ModelSearch.java" \
	"$here/ConversationHtmlTest.java" "$here/ModelSearchTest.java" "$here/ModelChoiceTest.java" \
	"$here/ProviderAuthPromptsTest.java" "$here/EventsTest.java" "$here/WorkspaceRootTest.java" \
	"$here/AttachmentSelectionTest.java"
java -ea -cp "$out:$gson" com.opencode.eclipse.ui.ConversationHtmlTest
java -ea -cp "$out:$gson" com.opencode.eclipse.ui.ModelSearchTest
java -ea -cp "$out:$gson" com.opencode.eclipse.ui.ModelChoiceTest
java -ea -cp "$out:$gson" com.opencode.eclipse.ui.ProviderAuthPromptsTest
java -ea -cp "$out:$gson" com.opencode.eclipse.ui.EventsTest
java -ea -cp "$out:$gson" com.opencode.eclipse.ui.WorkspaceRootTest
java -ea -cp "$out:$gson" com.opencode.eclipse.ui.AttachmentSelectionTest
javac -d "$out" -cp "$gson:$root/com.opencode.eclipse.core/target/classes" "$here/OpenCodeRequestBodyTest.java"
java -ea -cp "$out:$gson:$root/com.opencode.eclipse.core/target/classes" com.opencode.eclipse.core.OpenCodeRequestBodyTest
javac -d "$out" -cp "$gson:$root/com.opencode.eclipse.core/target/classes" "$here/SessionDirectoryListingTest.java"
java -ea -cp "$out:$gson:$root/com.opencode.eclipse.core/target/classes" com.opencode.eclipse.core.SessionDirectoryListingTest
javac -d "$out" -cp "$gson:$root/com.opencode.eclipse.core/target/classes" "$here/PermissionRelayTest.java"
java -ea -cp "$out:$gson:$root/com.opencode.eclipse.core/target/classes" com.opencode.eclipse.core.PermissionRelayTest
# Compiled from source, not target/classes: both tests reach package-private startup helpers.
javac -d "$out" -cp "$gson" "$root/com.opencode.eclipse.core/src/com/opencode/eclipse/core"/*.java \
	"$here/ServerStartupTest.java" "$here/ServerStreamTest.java"
java -ea -cp "$out:$gson" com.opencode.eclipse.core.ServerStartupTest
java -ea -Dopencode.eventTimeoutSeconds=2 -Dopencode.stallTimeoutSeconds=2 \
	-cp "$out:$gson" com.opencode.eclipse.core.ServerStreamTest
javac -d "$out" -cp "$gson:$root/com.opencode.eclipse.core/target/classes" \
	"$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/SlashCommands.java" "$here/SlashCommandsTest.java"
java -ea -cp "$out:$gson:$root/com.opencode.eclipse.core/target/classes" com.opencode.eclipse.ui.SlashCommandsTest
javac -d "$out" "$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/MessageQueue.java" "$here/MessageQueueTest.java"
java -ea -cp "$out" com.opencode.eclipse.ui.MessageQueueTest
javac -d "$out" -cp "$gson" \
	"$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/QuestionAnswers.java" "$here/QuestionAnswersTest.java"
java -ea -cp "$out:$gson" com.opencode.eclipse.ui.QuestionAnswersTest
javac -d "$out" -cp "$gson" \
	"$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/ChildSessionTracker.java" "$here/ChildSessionTrackerTest.java"
java -ea -cp "$out:$gson" com.opencode.eclipse.ui.ChildSessionTrackerTest

if [[ -n "${DISPLAY:-}" && -n "$swt_api" && -n "$swt_gtk" ]]; then
  plugin=$(ls -t "$root/com.opencode.eclipse.ui/target/"com.opencode.eclipse.ui-*-SNAPSHOT.jar 2>/dev/null | head -1)
  eclipse_plugins=$(dirname "$swt_api")
  echo "== model picker lifecycle =="
  javac -d "$out" -cp "$swt_api:$swt_gtk" \
    "$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/ModelSearch.java" \
    "$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/ModelPicker.java" \
    "$here/ModelPickerTest.java"
  java -ea -cp "$out:$swt_api:$swt_gtk" com.opencode.eclipse.ui.ModelPickerTest
  echo "== attached files bar height policy =="
  javac -d "$out" -cp "$swt_api:$swt_gtk" \
    "$root/com.opencode.eclipse.ui/src/com/opencode/eclipse/ui/AttachedFilesBar.java" \
    "$here/AttachedFilesBarTest.java"
  java -ea -cp "$out:$swt_api:$swt_gtk" com.opencode.eclipse.ui.AttachedFilesBarTest
	javac -d "$out" -cp "$plugin:$eclipse_plugins/*:$gson" "$here/SessionMonitorStateTest.java"
	java -ea -cp "$out:$plugin:$eclipse_plugins/*:$gson" com.opencode.eclipse.ui.SessionMonitorStateTest
	javac -d "$out" -cp "$plugin:$eclipse_plugins/*:$gson:$root/com.opencode.eclipse.core/target/classes" "$here/AgentDisplayNameTest.java"
	java -ea -cp "$out:$plugin:$eclipse_plugins/*:$gson:$root/com.opencode.eclipse.core/target/classes" com.opencode.eclipse.ui.AgentDisplayNameTest
	javac -d "$out" -cp "$plugin:$eclipse_plugins/*:$gson:$root/com.opencode.eclipse.core/target/classes" "$here/PromptBuilderTest.java"
	java -ea -cp "$out:$plugin:$eclipse_plugins/*:$gson:$root/com.opencode.eclipse.core/target/classes" com.opencode.eclipse.ui.PromptBuilderTest
	javac -d "$out" -cp "$plugin:$eclipse_plugins/*:$gson:$root/com.opencode.eclipse.core/target/classes" "$here/CommandRouterMergeTest.java"
	java -ea -cp "$out:$plugin:$eclipse_plugins/*:$gson:$root/com.opencode.eclipse.core/target/classes" com.opencode.eclipse.ui.CommandRouterMergeTest
	javac -d "$out" -cp "$plugin:$eclipse_plugins/*" \
	  "$here/OpenSettingsPathTest.java"
	java -ea -cp "$out:$plugin:$eclipse_plugins/*" \
	  com.opencode.eclipse.ui.OpenSettingsPathTest
	javac -d "$out" -cp "$plugin:$eclipse_plugins/*:$gson" \
	  "$here/FakePreferences.java" "$here/SessionRestoreStoreTest.java" "$here/PermissionDecisionsTest.java"
	java -ea -cp "$out:$plugin:$eclipse_plugins/*:$gson" com.opencode.eclipse.ui.SessionRestoreStoreTest
	java -ea -cp "$out:$plugin:$eclipse_plugins/*:$gson" com.opencode.eclipse.ui.PermissionDecisionsTest

  echo "== changed-file undo =="
  javac -d "$out" -cp "$plugin:$eclipse_plugins/*" "$here/DiffsTest.java"
  java -ea -cp "$out:$plugin:$eclipse_plugins/*" com.opencode.eclipse.ui.DiffsTest
	javac -d "$out" -cp "$plugin:$eclipse_plugins/*" "$here/ChangedFilesBarTest.java"
	java -ea -cp "$out:$plugin:$eclipse_plugins/*" com.opencode.eclipse.ui.ChangedFilesBarTest

  echo "== real SWT Browser DOM smoke (300 messages) =="
  javac -d "$out" -cp "$plugin:$swt_api:$swt_gtk:$gson" "$here/ConversationBrowserSmoke.java"
  java -ea -cp "$out:$plugin:$swt_api:$swt_gtk:$gson" com.opencode.eclipse.ui.ConversationBrowserSmoke
else
  echo "== Browser smoke skipped: DISPLAY/SWT unavailable =="
fi

if [[ "${OPENCODE_LIVE_TESTS:-0}" == "1" ]]; then
  echo "== live OpenCode integration tests =="
  core="$root/com.opencode.eclipse.core/src/com/opencode/eclipse/core"
  javac -d "$out" -cp "$gson" "$core"/*.java \
	"$here/ItTest.java" "$here/AgenticTest.java" "$here/AbortContinueTest.java" \
	"$here/ReliabilityLiveTest.java"
	javac -d "$out" -cp "$out:$gson" "$here/SlashCommandLiveTest.java"
	javac -d "$out" -cp "$out:$gson" "$here/SessionLifecycleLiveTest.java"
	javac -d "$out" -cp "$out:$gson" "$here/QuestionLiveTest.java"
	# Needs the CLI, but no provider and no model request.
	javac -d "$out" -cp "$out:$gson" "$here/ServerPortLiveTest.java"
  workspace=$(mktemp -d)
  trap 'rm -rf "$out" "$workspace"' EXIT
  echo '{"model":"github-copilot/claude-sonnet-4.6"}' > "$workspace/opencode.json"
  (cd "$workspace" && java -ea -cp "$out:$gson" com.opencode.eclipse.core.ServerPortLiveTest)
  (cd "$workspace" && java -ea -cp "$out:$gson" ItTest)
  (cd "$workspace" && java -ea -cp "$out:$gson" AgenticTest)
  (cd "$workspace" && java -ea -cp "$out:$gson" AbortContinueTest)
  (cd "$workspace" && java -ea -cp "$out:$gson" ReliabilityLiveTest)
	(cd "$workspace" && java -ea -cp "$out:$gson" SlashCommandLiveTest)
	(cd "$workspace" && java -ea -cp "$out:$gson" SessionLifecycleLiveTest)
	(cd "$workspace" && java -ea -cp "$out:$gson" QuestionLiveTest)
fi

echo "ALL TESTS OK"
