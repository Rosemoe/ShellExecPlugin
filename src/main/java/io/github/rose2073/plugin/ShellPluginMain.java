package io.github.rose2073.plugin;

import kotlin.coroutines.Continuation;
import net.mamoe.mirai.console.command.Command;
import net.mamoe.mirai.console.command.CommandManager;
import net.mamoe.mirai.console.command.CommandSender;
import net.mamoe.mirai.console.plugins.Config;
import net.mamoe.mirai.console.plugins.PluginBase;
import net.mamoe.mirai.message.GroupMessage;
import net.mamoe.mirai.message.GroupMessageEvent;
import net.mamoe.mirai.message.data.At;
import net.mamoe.mirai.message.data.MessageUtils;
import net.mamoe.mirai.message.data.PlainText;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unused")
public class ShellPluginMain extends PluginBase {

    private final static List<String> DEFAULT_DARK_LIST;
    private Config config;
    private final ThreadInt runningThreadCount = new ThreadInt();
    private final ThreadInt idAllocator = new ThreadInt();
    private final Map<Integer,Process> processMap = new ConcurrentHashMap<>();

    private static class ThreadInt {

        volatile int value = 0;

        synchronized void increase() {
            value++;
        }

        synchronized void decrease() {
            value--;
            if(value < 0) {
                value = 0;
            }
        }

        synchronized boolean check(int another) {
            return value >= another;
        }

        public int getValue() {
            return value;
        }
    }

    static {
        DEFAULT_DARK_LIST = new ArrayList<>();
        DEFAULT_DARK_LIST.add("rm");
        DEFAULT_DARK_LIST.add("sudo");
        DEFAULT_DARK_LIST.add("shutdown");
        DEFAULT_DARK_LIST.add("boot");
    }

    public void onLoad() {
        loadPluginInternal();
        getLogger().info("插件已加载");
        initEvents();
    }

    private void loadPluginInternal() {
        config = loadConfig("settings.yml");
        config.setIfAbsent("msgPrefix","bash/");
        config.setIfAbsent("msgPrefixOverride","superBash/");
        config.setIfAbsent("msgPrefixPipe","pipe/");
        config.setIfAbsent("msgPrefixPipeOverride","superPipe/");
        config.setIfAbsent("allowedGroups", new ArrayList<Long>());
        config.setIfAbsent("darkListKeywords",DEFAULT_DARK_LIST);
        config.setIfAbsent("concurrentCommandCount",10);
        config.setIfAbsent("charset","utf-8");
        config.setIfAbsent("superCommanders",new ArrayList<Long>());
        config.setIfAbsent("darkListUsers",new ArrayList<Long>());
        config.save();
    }

    private boolean contains(List<Long> list,Long value) {
        for(Long l : list) {
            if(l.longValue() == value.longValue()) {
                return true;
            }
        }
        return false;
    }

    private void setupSender(InputStream is, GroupMessageEvent event) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is,config.getString("charset")));
            String line;
            while((line = br.readLine()) != null) {
                if(!line.trim().isEmpty()) {
                    event.getGroup().sendMessage(line);
                }
            }
            br.close();
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void input(int sessionId,String pure,GroupMessageEvent event) {
        Process process = processMap.get(sessionId);
        if(process != null) {
            try {
                process.getOutputStream().write((pure + '\n').getBytes());
                event.getGroup().sendMessage("输入成功");
            } catch (IOException e) {
                e.printStackTrace();
                event.getGroup().sendMessage("输入失败:" + e.toString());
            }
        }else{
            event.getGroup().sendMessage("找不到sessionId = " + sessionId + " 的进程");
        }
    }

    private synchronized void registerProcess(int sessionId,Process process) {
        processMap.put(sessionId,process);
    }

    private synchronized void unregisterProcess(int sessionId) {
        processMap.remove(sessionId);
    }

    private boolean pipedExecuteCheck(GroupMessageEvent event) {
        String msgContent = event.getMessage().contentToString().trim();
        //Clear source tag
        if(msgContent.startsWith("[mirai:source")) {
            msgContent = msgContent.substring(msgContent.indexOf("]") + 1);
        }
        if(contains(config.getLongList("darkListUsers"),event.getSender().getId())) {
            return false;
        }
        if(msgContent.startsWith("write:") && contains(config.getLongList("allowedGroups"),event.getGroup().getId())) {
            int idx = msgContent.indexOf("/");
            if(idx == -1) {
                return false;
            }
            String session = msgContent.substring(6,idx).trim();
            try {
                input(Integer.parseInt(session),msgContent.substring(idx + 1),event);
            }catch (NumberFormatException e) {
                event.getGroup().sendMessage("数字格式错误");
            }
            return true;
        }
        String prefix = config.getString("msgPrefixPipe");
        String prefix2 = config.getString("msgPrefixPipeOverride");
        if(msgContent.startsWith(prefix) || msgContent.startsWith(prefix2)) {
            boolean isOverride = false;
            //This is exactly what we will execute
            if(msgContent.startsWith(prefix)) {
                msgContent = msgContent.substring(prefix.length());
            }else{
                msgContent = msgContent.substring(prefix2.length());
                isOverride = true;
            }
            //Check group id
            if(contains(config.getLongList("allowedGroups"),event.getGroup().getId())) {
                //Check keywords
                if(isOverride) {
                    List<Long> commanders = config.getLongList("superCommanders");
                    if(!contains(commanders,event.getSender().getId())) {
                        event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()), new PlainText("你无权使用本命令")));
                        return false;
                    }
                }else {
                    List<String> darkListKeywords = config.getStringList("darkListKeywords");
                    for (String darkListKeyword : darkListKeywords) {
                        if (msgContent.contains(darkListKeyword)) {
                            event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()), new PlainText("你的指令含有禁止的关键字:" + darkListKeyword + ",因此,你的指令已取消")));
                            return false;
                        }
                    }
                }
                final ThreadInt heldObject = runningThreadCount;
                final int id;
                //Limit thread count
                synchronized (this) {
                    if(heldObject.check(config.getInt("concurrentCommandCount"))) {
                        event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()), new PlainText("当前正在执行的命令数量达到最大值,因此你的操作被取消")));
                        return false;
                    }
                    heldObject.increase();
                    idAllocator.increase();
                    event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()),new PlainText("你的指令正在执行中.Session ID = " + idAllocator.getValue())));
                    id = idAllocator.getValue();
                }
                //Run shell
                String finalMsgContent = msgContent;
                getScheduler().async(() -> {
                    getLogger().info("开始执行Shell,命令:" + finalMsgContent);
                    try {
                        boolean isLinux = !System.getProperty("os.name").toLowerCase().contains("windows");
                        Process process = isLinux ? Runtime.getRuntime().exec("sh -c " + finalMsgContent) : Runtime.getRuntime().exec(finalMsgContent);
                        registerProcess(id,process);
                        getScheduler().async(() -> setupSender(process.getInputStream(),event));
                        getScheduler().async(() -> setupSender(process.getErrorStream(),event));
                        int exitCode = process.waitFor();
                        event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()),new PlainText("Session ID = " + id + "的进程已结束,exitCode = " + exitCode)));
                    } catch (IOException|InterruptedException e) {
                        e.printStackTrace();
                        event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()),new PlainText("执行失败(Java层错误):"),new PlainText(e.toString())));
                    }
                    unregisterProcess(id);
                    getLogger().info("执行结束");
                    //Stop
                    synchronized (ShellPluginMain.this) {
                        heldObject.decrease();
                    }
                });
                return true;
            }else{
                getLogger().info("未开启本群的Shell功能:" + event.getGroup().getId());
            }
        }
        return false;
    }

    public void initEvents() {
        getEventListener().subscribeAlways((GroupMessageEvent.class),(GroupMessageEvent event) -> {
            if(pipedExecuteCheck(event)) {
                return;
            }
            String msgContent = event.getMessage().contentToString().trim();
            //Clear source tag
            if(msgContent.startsWith("[mirai:source")) {
                msgContent = msgContent.substring(msgContent.indexOf("]") + 1);
            }
            if(contains(config.getLongList("darkListUsers"),event.getSender().getId())) {
                return;
            }
            //Check message prefix
            String prefix = config.getString("msgPrefix");
            String prefix2 = config.getString("msgPrefixOverride");
            if(msgContent.startsWith(prefix) || msgContent.startsWith(prefix2)) {
                boolean isOverride = false;
                //This is exactly what we will execute
                if(msgContent.startsWith(prefix)) {
                    msgContent = msgContent.substring(prefix.length());
                }else{
                    msgContent = msgContent.substring(prefix2.length());
                    isOverride = true;
                }
                //Check group id
                if(contains(config.getLongList("allowedGroups"),event.getGroup().getId())) {
                    //Check keywords
                    if(isOverride) {
                        List<Long> commanders = config.getLongList("superCommanders");
                        if(!contains(commanders,event.getSender().getId())) {
                            event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()), new PlainText("你无权使用本命令")));
                            return;
                        }
                    }else {
                        List<String> darkListKeywords = config.getStringList("darkListKeywords");
                        for (String darkListKeyword : darkListKeywords) {
                            if (msgContent.contains(darkListKeyword)) {
                                event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()), new PlainText("你的指令含有禁止的关键字:" + darkListKeyword + ",因此,你的指令已取消")));
                                return;
                            }
                        }
                    }
                    final ThreadInt heldObject = runningThreadCount;
                    //Limit thread count
                    synchronized (this) {
                        if(heldObject.check(config.getInt("concurrentCommandCount"))) {
                            event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()), new PlainText("当前正在执行的命令数量达到最大值,因此你的操作被取消")));
                            return;
                        }
                        heldObject.increase();
                    }
                    //Start!
                    event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()),new PlainText("你的指令正在执行中,请稍等")));
                    //Run shell
                    String finalMsgContent = msgContent;
                    getScheduler().async(() -> {
                        getLogger().info("开始执行Shell,命令:" + finalMsgContent);
                        try {
                            boolean isLinux = !System.getProperty("os.name").toLowerCase().contains("windows");
                            Process process = isLinux ? Runtime.getRuntime().exec("sh -c " + finalMsgContent) : Runtime.getRuntime().exec(finalMsgContent);
                            int exitCode = process.waitFor();
                            StringBuilder output = new StringBuilder();
                            output.append("Shell执行结果");
                            if(exitCode == 0) {
                                output.append("(正常)");
                            }else{
                                output.append("(异常退出 exitCode = ").append(exitCode).append(")");
                            }
                            output.append(":\n");
                            readContentOfStream(output,process.getInputStream());
                            readContentOfStream(output,process.getErrorStream());
                            event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()),new PlainText(output.toString())));
                        } catch (IOException|InterruptedException e) {
                            e.printStackTrace();
                            event.getGroup().sendMessage(MessageUtils.newChain(new At(event.getSender()),new PlainText("执行失败(Java层错误):"),new PlainText(e.toString())));
                        }
                        getLogger().info("执行结束");
                        //Stop
                        synchronized (ShellPluginMain.this) {
                            heldObject.decrease();
                        }
                    });
                }else{
                    getLogger().info("未开启本群的Shell功能:" + event.getGroup().getId());
                }
            }
        });
        try {
            CommandManager.INSTANCE.register(this, new Command() {
                @NotNull
                @Override
                public String getName() {
                    return "shellConfig";
                }

                @NotNull
                @Override
                public List<String> getAlias() {
                    return new ArrayList<>();
                }

                @NotNull
                @Override
                public String getDescription() {
                    return "调整ShellExec插件的配置";
                }

                @NotNull
                @Override
                public String getUsage() {
                    return "/shellConfig keywordAdd <关键字>      添加黑名单关键字\n" +
                            "/shellConfig keywordList             获取黑名单关键字列表\n" +
                            "/shellConfig keywordRemove <序号>    删除指定序号的关键字\n" +
                            "/shellConfig keywordClear            删除所有关键字(请谨慎操作)\n" +
                            "/shellConfig groupAdd <群号>         添加要监听的群\n" +
                            "/shellConfig groupList               获取监听的群的列表\n" +
                            "/shellConfig groupRemove <群号>      取消监听此群\n" +
                            "/shellConfig charset <字符集名称>     设置字符集名称,如果出现乱码请使用它来修改\n" +
                            "/shellConfig superUserAdd <QQ号码>   添加超级用户,可以无视关键字检查执行命令\n" +
                            "/shellConfig superUserList           列出超级用户QQ号列表\n" +
                            "/shellConfig superUserRemove <QQ号码> 删除一个超级用户\n" +
                            "/shellConfig banAdd <QQ>              封禁用户\n" +
                            "/shellConfig banList                  封禁列表\n" +
                            "/shellConfig banRemove <QQ>           解除封禁\n" +
                            "/shellConfig limit <最大线程数>        限定同时执行的最大线程数目\n" +
                            "/shellConfig prefix <前缀>            设定脚本执行的前缀\n" +
                            "/shellConfig pipePrefix <前缀>        流式执行前缀\n" +
                            "/shellConfig pipeSuperPrefix <前缀>    流式执行前缀,无视关键字\n" +
                            "/shellConfig superPrefix <前缀>       设定无视关键字限制执行脚本的前缀\n" +
                            "/shellConfig reload                   重载配置\n" +
                            "ShellExec 1.0.0 - By AbstractRose";
                }

                @NotNull
                @Override
                public Object onCommand(@NotNull CommandSender commandSender, @NotNull List<String> list, @NotNull Continuation<? super Boolean> continuation) {
                    if (list.size() == 0) {
                        commandSender.appendMessage("缺少命令动作,/shellConfig help查看指令表");
                        return false;
                    }
                    switch (list.get(0)) {
                        case "keywordAdd": {
                            List<String> kws = config.getStringList("darkListKeywords");
                            if (!kws.contains(list.get(1))) {
                                kws.add(list.get(1));
                            }
                            config.set("darkListKeywords", kws);
                            commandSender.appendMessage("成功添加关键字");
                            break;
                        }
                        case "keywordList": {
                            List<String> kws = config.getStringList("darkListKeywords");
                            for (int i = 0; i < kws.size(); i++) {
                                commandSender.appendMessage("[Keyword " + i + "]" + kws.get(i));
                            }
                            break;
                        }
                        case "keywordRemove": {
                            List<String> kws = config.getStringList("darkListKeywords");
                            kws.remove(Integer.parseInt(list.get(1)));
                            config.set("darkListKeywords", kws);
                            commandSender.appendMessage("成功删除关键字");
                            break;
                        }
                        case "keywordClear": {
                            config.set("darkListKeywords", new ArrayList<>());
                            commandSender.appendMessage("成功清空关键字");
                            break;
                        }
                        case "prefix": {
                            config.set("msgPrefix", list.get(1));
                            commandSender.appendMessage("成功设置命令前缀");
                            break;
                        }
                        case "superPrefix": {
                            config.set("msgPrefixOverride", list.get(1));
                            commandSender.appendMessage("成功设置超级命令前缀");
                            break;
                        }
                        case "pipePrefix": {
                            config.set("msgPrefixPipe", list.get(1));
                            commandSender.appendMessage("成功设置命令前缀");
                            break;
                        }
                        case "pipeSuperPrefix": {
                            config.set("msgPrefixPipeOverride", list.get(1));
                            commandSender.appendMessage("成功设置超级命令前缀");
                            break;
                        }
                        case "charset": {
                            try {
                                Charset.forName(list.get(1));
                            } catch (UnsupportedCharsetException e) {
                                commandSender.appendMessage("失败:找不到这个字符集,请检查它的名称");
                                break;
                            }
                            config.set("charset", list.get(1));
                            commandSender.appendMessage("成功设置流字符集");
                            break;
                        }
                        case "limit": {
                            try {
                                config.set("concurrentCommandCount", Integer.parseInt(list.get(1)));
                                commandSender.appendMessage("成功设置最大线程数目");
                            } catch (NumberFormatException e) {
                                commandSender.appendMessage("数字格式错误");
                            }
                            break;
                        }
                        case "groupAdd": {
                            List<Long> kws = config.getLongList("allowedGroups");
                            try {
                                if (!contains(kws,Long.parseLong(list.get(1)))) {
                                    kws.add(Long.parseLong(list.get(1)));
                                }
                            } catch (NumberFormatException e) {
                                commandSender.appendMessage("数字格式错误");
                                break;
                            }
                            config.set("allowedGroups", kws);
                            commandSender.appendMessage("成功添加群到监听列表");
                            break;
                        }
                        case "groupList": {
                            List<Long> kws = config.getLongList("allowedGroups");
                            for (Long kw : kws) {
                                commandSender.appendMessage("- " + kw);
                            }
                            break;
                        }
                        case "groupRemove": {
                            List<Long> kws = config.getLongList("allowedGroups");
                            try {
                                kws.remove(Long.parseLong(list.get(1)));
                            } catch (NumberFormatException e) {
                                commandSender.appendMessage("数字格式错误");
                                break;
                            }
                            config.set("allowedGroups", kws);
                            commandSender.appendMessage("成功移除群");
                            break;
                        }
                        case "superUserAdd": {
                            List<Long> kws = config.getLongList("superCommanders");
                            try {
                                if (!contains(kws,Long.parseLong(list.get(1)))) {
                                    kws.add(Long.parseLong(list.get(1)));
                                }
                            } catch (NumberFormatException e) {
                                commandSender.appendMessage("数字格式错误");
                                break;
                            }
                            config.set("superCommanders", kws);
                            commandSender.appendMessage("成功添加超级用户");
                            break;
                        }
                        case "superUserList": {
                            List<Long> kws = config.getLongList("superCommanders");
                            for (Long kw : kws) {
                                commandSender.appendMessage("- " + kw);
                            }
                            break;
                        }
                        case "superUserRemove": {
                            List<Long> kws = config.getLongList("superCommanders");
                            try {
                                kws.remove(Long.parseLong(list.get(1)));
                            } catch (NumberFormatException e) {
                                commandSender.appendMessage("数字格式错误");
                                break;
                            }
                            config.set("superCommanders", kws);
                            commandSender.appendMessage("成功移除超级用户");
                            break;
                        }
                        case "banAdd": {
                            List<Long> kws = config.getLongList("darkListUsers");
                            try {
                                if (!contains(kws,Long.parseLong(list.get(1)))) {
                                    kws.add(Long.parseLong(list.get(1)));
                                }
                            } catch (NumberFormatException e) {
                                commandSender.appendMessage("数字格式错误");
                                break;
                            }
                            config.set("darkListUsers", kws);
                            commandSender.appendMessage("成功添加封禁用户");
                            break;
                        }
                        case "banList": {
                            List<Long> kws = config.getLongList("darkListUsers");
                            for (Long kw : kws) {
                                commandSender.appendMessage("- " + kw);
                            }
                            break;
                        }
                        case "banRemove": {
                            List<Long> kws = config.getLongList("darkListUsers");
                            try {
                                kws.remove(Long.parseLong(list.get(1)));
                            } catch (NumberFormatException e) {
                                commandSender.appendMessage("数字格式错误");
                                break;
                            }
                            config.set("darkListUsers", kws);
                            commandSender.appendMessage("成功解封用户");
                            break;
                        }
                        case "help": {
                            commandSender.appendMessage(getUsage());
                            break;
                        }
                        case "reload": {
                            loadPluginInternal();
                            commandSender.appendMessage("重载配置完毕");
                            break;
                        }
                        default:
                            commandSender.appendMessage("找不到此命令");
                            return false;
                    }
                    config.save();
                    return true;
                }
            });
        }catch (IllegalStateException ignored) {
            getLogger().warning("注册命令时,该命令被占用:shellConfig");
        }
    }

    private void readContentOfStream(StringBuilder sb, InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(inputStream,config.getString("charset")));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        br.close();
    }

}