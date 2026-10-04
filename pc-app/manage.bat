@echo off
chcp 65001 >nul
setlocal
set "PYW=C:\Users\Lenovo\.workbuddy\binaries\python\versions\3.13.12\pythonw.exe"
set "PY=C:\Users\Lenovo\.workbuddy\binaries\python\versions\3.13.12\python.exe"
set "AUTO=C:\Users\Lenovo\Documents\SaveAssistantPC\app\tools\autostart.py"

:MENU
cls
echo ============================================================
echo   保存助手 · 电脑端服务管理
echo ============================================================
echo.
"%PY%" "%AUTO%" status
echo.
echo ------------------------------------------------------------
echo   [1] 启动服务（后台，不开浏览器）
echo   [2] 停止服务
echo   [3] 打开工作台界面
echo   [4] 安装开机自启
echo   [5] 取消开机自启
echo   [6] 查看运行日志（最后 30 行）
echo   [0] 退出
echo ------------------------------------------------------------
echo.
set /p "CH=请选择："

if "%CH%"=="1" "%PY%" "%AUTO%" start & timeout /t 2 >nul & goto MENU
if "%CH%"=="2" "%PY%" "%AUTO%" stop  & timeout /t 2 >nul & goto MENU
if "%CH%"=="3" (
    start "" "%PYW%" "C:\Users\Lenovo\Documents\SaveAssistantPC\app\server.py"
    timeout /t 2 >nul & goto MENU
)
if "%CH%"=="4" "%PY%" "%AUTO%" install   & timeout /t 3 >nul & goto MENU
if "%CH%"=="5" "%PY%" "%AUTO%" uninstall & timeout /t 3 >nul & goto MENU
if "%CH%"=="6" (
    echo.
    echo --- server.log 最后 30 行 ---
    powershell -NoProfile -Command "Get-Content -Path 'C:\Users\Lenovo\Documents\SaveAssistantPC\app\server.log' -Tail 30"
    echo.
    pause
    goto MENU
)
if "%CH%"=="0" exit /b 0

echo 无效选择。
timeout /t 2 >nul
goto MENU
