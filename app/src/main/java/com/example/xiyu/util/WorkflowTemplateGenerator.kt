package com.example.xiyu.util

object WorkflowTemplateGenerator {

    fun generateAndroidBuildWorkflow(branchName: String = "main"): String {
        val targetBranch = branchName.trim().ifBlank { "main" }
        return """
name: Android CI Build & Log Monitor

on:
  push:
    branches: [ $targetBranch ]
  pull_request:
    branches: [ $targetBranch ]
  workflow_dispatch:

jobs:
  build:
    name: Build Debug APK & Capture Gradle Logs
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Setup Gradle cache
        uses: gradle/actions/setup-gradle@v4

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew || true

      - name: Build Debug APK (with gradle-build.log)
        run: |
          set -o pipefail
          ./gradlew assembleDebug --stacktrace 2>&1 | tee gradle-build.log

      - name: Upload APK & Error Logs (Always)
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: kruzenlab-build-output
          path: |
            app/build/outputs/apk/**/*.apk
            gradle-build.log
          if-no-files-found: warn
          retention-days: 14
        """.trimIndent() + "\n"
    }
}
