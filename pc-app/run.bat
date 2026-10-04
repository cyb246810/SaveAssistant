@echo off
chcp 65001 >nul
REM ===========================================================================
REM  保存助手 · 电脑端传输工作台
REM
REM  这个入口的语义是「我要打开工作台界面」：
REM    - 服务已经在跑  -> 直接打开浏览器里的工作台（不会起第二个进程）
REM    - 服务没在跑    -> 起服务并打开工作台
REM    - 关掉这个窗口  -> 服务照旧在跑（--background 由自启项负责常驻）
REM
REM  必须走 pythonw：Windows 防火墙的入站允许规则只针对 pythonw.exe。
REM ===========================================================================

set "PYW=C:\Users\Lenovo\.workbuddy\binaries\python\versions\3.13.12\pythonw.exe"
set "SRV=C:\Users\Lenovo\Documents\SaveAssistantPC\app\server.py"

if not exist "%PYW%" (
    echo 找不到 pythonw.exe：%PYW%
    pause
    exit /b 1
)

REM 不带 --background：这是「我要看界面」的路径，会自己按需开浏览器
start "" "%PYW%" "%SRV%" %*

exit /b 0
