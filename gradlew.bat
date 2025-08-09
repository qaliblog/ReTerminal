@ECHO OFF

:: This is a placeholder Windows gradle wrapper script ensuring repository completeness.
:: No changes needed for Termux. The POSIX wrapper (gradlew) is used on Android.

IF NOT EXIST gradle\wrapper\gradle-wrapper.jar (
  ECHO Gradle wrapper JAR not found.
  EXIT /B 1
)

SET DIR=%~dp0
SET APP_BASE_NAME=%~n0
SET CLASSPATH=%DIR%gradle\wrapper\gradle-wrapper.jar

SET DEFAULT_JVM_OPTS=-Xmx64m -Xms64m

java %DEFAULT_JVM_OPTS% -Dorg.gradle.appname=%APP_BASE_NAME% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
