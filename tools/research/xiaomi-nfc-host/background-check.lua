-- Verify NSH background execution outside the watchface thread, no NFC action.
local base="/data/quickapp/files/com.meta.s4nativeprobe"
local ready=io.open(base.."/ready.txt","r");if not ready then return end;ready:close()
local done=io.open(base.."/background-check-done.txt","r");if done then done:close();return end
local f=io.open(base.."/background-check.sh","w");if not f then return end
f:write("sleep 3\necho S4_BACKGROUND_DONE > "..base.."/version.txt\n")
f:close()
local before=os.time()
local ok,a,b,c=pcall(function()return os.execute("sh "..base.."/background-check.sh > "..base.."/host-help.txt &")end)
local after=os.time()
f=io.open(base.."/lua-result.txt","w")
if f then f:write("NSH background-only test; no NFC action\nlaunch_seconds="..tostring(after-before).."\nresult="..tostring(ok)..","..tostring(a)..","..tostring(b)..","..tostring(c));f:close()end
f=io.open(base.."/background-check-done.txt","w");if f then f:write("done");f:close()end
