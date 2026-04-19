@echo off
setlocal enabledelayedexpansion

:: 定义路径
set APK_PATH=%~dp0\..\app\build\outputs\apk\release\app-release-unsigned.apk
set OUTPUT_JAR=%~dp0\pp.jar

:: 1. 检查编译产物是否存在
if not exist "%APK_PATH%" (
    echo [ERROR] 未找到编译生成的 APK 文件，请检查 Gradle 编译步骤。
    exit /b 1
)

:: 2. 清理旧数据
rd /s/q "%~dp0\Smali_classes" 2>nul

:: 3. 提取 Smali 指令
echo [INFO] 正在从 APK 提取代码...
java -jar "%~dp0\3rd\apktool_2.11.0.jar" d -f --only-main-classes "%APK_PATH%" -o "%~dp0\Smali_classes"

:: 4. 移动代码到 Jar 结构文件夹
rd /s/q "%~dp0\spider.jar\smali\com\github\catvod\spider" 2>nul
if not exist "%~dp0\spider.jar\smali\com\github\catvod\" md "%~dp0\spider.jar\smali\com\github\catvod\"
move "%~dp0\Smali_classes\smali\com\github\catvod\spider" "%~dp0\spider.jar\smali\com\github\catvod\"

:: 5. 使用 Apktool 回编译为 Dex 格式的 Jar
echo [INFO] 正在打包为 pp.jar...
java -jar "%~dp0\3rd\apktool_2.11.0.jar" b "%~dp0\spider.jar" -c

:: 6. 整理产物并生成 MD5
if not exist "%~dp0\spider.jar\dist\dex.jar" (
    echo [ERROR] 打包失败，未生成 dex.jar。
    exit /b 1
)
move /y "%~dp0\spider.jar\dist\dex.jar" "%OUTPUT_JAR%"
certUtil -hashfile "%OUTPUT_JAR%" MD5 | find /i /v "md5" | find /i /v "certutil" > "%OUTPUT_JAR%.md5"

echo [SUCCESS] pp.jar 制作完成！
