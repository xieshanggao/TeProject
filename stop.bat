@echo off
chcp 65001 >nul

rem 查找并关闭 TeProjectApplication 进程
for /f "tokens=1,2 delims=," %%a in ('wmic process where "commandline like '%%TeProjectApplication%%' and name='java.exe'" get processid^,commandline /format:csv ^| findstr /i "TeProjectApplication"') do (
    echo 正在结束进程 PID: %%b
    taskkill /F /PID %%b 2>nul
    if errorlevel 1 (
        echo 结束进程失败，请检查是否以管理员身份运行
    ) else (
        echo 进程已结束
    )
)

rem 兜底：按名称关闭（会同时关闭当前机器上所有名为 TeProjectApplication 的 Java 进程）
rem taskkill /F /IM TeProjectApplication.exe 2>nul

pause
