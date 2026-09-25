@echo off
REM 保存助手 · 电脑端传输工作台 —— 带控制台启动（便于查看日志/调试）
REM
REM 日常常开建议用 pythonw.exe（无控制台窗口），本脚本用 python.exe 便于看输出。
REM
REM 注意：Windows 防火墙的入站允许规则是按「程序路径」逐条匹配的。
REM 如果你之前只给 pythonw.exe 放行了入站，换成 python.exe 启动时手机就连不上
REM （表现为连接超时），需要再给 python.exe 加一条入站允许规则。
setlocal
cd /d "%~dp0"
python "%~dp0server.py" %*
if errorlevel 1 (
  echo.
  echo 启动失败：请确认已安装 Python 3.8+，并把 python 加入 PATH。
)
pause
