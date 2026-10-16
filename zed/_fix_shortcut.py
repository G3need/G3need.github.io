import win32com.client
sh = win32com.client.Dispatch('WScript.Shell')
p = r"C:\Users\Geneed\AppData\Roaming\Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar\Vivaldi.lnk"
s = sh.CreateShortcut(p)
print('OLD args:', repr(s.Arguments))
s.Arguments = '--remote-debugging-port=9222 --remote-allow-origins=*'
s.Save()
print('NEW args:', repr(s.Arguments))