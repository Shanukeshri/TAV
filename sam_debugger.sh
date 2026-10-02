#!/usr/bin/env bash
echo "======================================"
echo "    SAM (Teachable Agent) Debugger    "
echo "======================================"
echo "Connecting to ADB and fetching logs..."

# Only get SAM-related logs from the device
adb logcat -d -s "SAM_*" "AgentController" "AutomationBridge" "TAV_ACCESSIBILITY" "VoiceRouter" "ModelManager" "LlamaCppBackend" "AgentForegroundService" "AgentRequestRouter" "TeachOverlay" "VoiceListeningOverlay" "AgentOverlay" | grep -v "W/" > sam_debug.log

echo "Done! The logs have been saved to sam_debug.log in this directory."
echo "You can read it using:"
echo "  cat sam_debug.log"
echo "or share it with the AI to diagnose the issue."
