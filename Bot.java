import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.time.LocalDateTime;

import static java.time.temporal.ChronoField.*;
import static net.dv8tion.jda.api.requests.GatewayIntent.*;

public class Bot extends ListenerAdapter{
    static final int MESSAGE_DELETION_LENGTH = 168; // default message deletion length
    static final int STATUS_CHANGE_DURATION = 300; // time [s] between status changes
    static Logger logger; // slf4j simple logger
    static JDA jda;
    static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(32); // scheduler for scheduled events
    public static void main(String[] args){
        logger = LoggerFactory.getLogger(Bot.class); // this entire block is logger and logfile creation
        try{
            File file = new File("data/logs/"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd'_'HHmmss"))+".log");
            file.createNewFile();
            System.setOut(new PrintStream(file));
        }catch(Exception e){
            System.out.println("Logger unable to be created.");
        }
        jda = JDABuilder.createLight(System.getenv("JAVABOT")).addEventListeners(new Bot()).setActivity(Activity.playing("Test")).enableIntents(GUILD_MEMBERS).setMemberCachePolicy(MemberCachePolicy.ALL).build(); // bot creation, token to be hidden
        addCommands();
        scheduler.schedule(Bot::scheduledStatusChanger, 3, TimeUnit.SECONDS); // 3 seconds, so it only executes after the main logic is loaded
        scheduler.schedule(Bot::automaticAppealer, 10, TimeUnit.SECONDS);
    }
    public static int curtime(){
        return (int) LocalDateTime.now().toEpochSecond(ZoneOffset.ofHours(1));
    }
    public static int untilNextTimeOfDay(int seconds){
        LocalDateTime time = LocalDateTime.now();
        LocalDateTime newtime = time.plusDays(boolToInt(seconds < time.get(SECOND_OF_DAY))).plusSeconds(seconds-time.get(SECOND_OF_DAY));
        return (int) ((int) ((int) newtime.toEpochSecond(ZoneOffset.ofHours(1)))-curtime());
    }
    public static void addCommands(){ // func for registering commands, self-explanatory
        jda.updateCommands().addCommands(
                Commands.slash("test", "test"),
                Commands.slash("set", "Set specific channel types, for more information provide \"info\" as argument.").setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL)).addOption(OptionType.STRING, "type", "Type of channel to set.", true, true).addOption(OptionType.CHANNEL, "channel", "The channel to set.", true).setGuildOnly(true),
                Commands.slash("ban", "Bans people. Automatic appeal in a set number of days.").setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS)).addOption(OptionType.USER, "banned", "The user to ban.", true).addOption(OptionType.STRING, "reason", "Ban reason.", false).addOption(OptionType.INTEGER, "deletiontime", "The duration, for which the banned user's messages are to be deleted, in hours. 168 or less.", false).setGuildOnly(true),
                Commands.slash("kick", "Kicks people.").setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.KICK_MEMBERS)).addOption(OptionType.USER, "kicked", "The user to kick.", true).addOption(OptionType.STRING, "reason", "Kick reason.", false).setGuildOnly(true),
                Commands.slash("unban", "Unbans people.").setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS)).addOption(OptionType.USER, "banned", "The user to unban.", true).setGuildOnly(true),
                Commands.slash("banappealset", "Sets the number of days to count until appeal. Input zero to disable appeals.").setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS)).addOption(OptionType.INTEGER, "days", "Number of days. Zero to disable. MAX = 366.", true).setGuildOnly(true),
                Commands.slash("banmessageset", "Set whether the bot should announce bans in main chat (arg 1) or in the banned user's DMs (arg 2).").setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS)).addOption(OptionType.BOOLEAN, "main", "Announcing bans in main chat.", false).addOption(OptionType.BOOLEAN, "dm", "Announcing bans in DMs.", false).setGuildOnly(true),
                Commands.slash("reload", "Reload file creation.").setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER, Permission.MANAGE_CHANNEL)).setGuildOnly(true)
        ).queue();
    }
    @Override
    public void onGuildJoin(GuildJoinEvent event){
        reloadFiles(event.getGuild()); // creates new data files, if necessary
    }
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event){ // mega-func for all slash commands, to be split later
        event.deferReply(); // universal deferreply for all commands
        switch(event.getName()){
            case "test": // to be deleted
                event.reply("Test.").setEphemeral(false).queue();
                break;
            case "set": // for server admins to set channels for specific output
                if(!event.getMember().hasPermission(Permission.MANAGE_CHANNEL)){
                    insufficientPermissionsStandardResponseSlashCommand(event);
                    break;
                }
                String type = event.getOption("type").getAsString().toLowerCase();
                if(type.equals("info")){
                    event.reply("This command changes the special channels for the server (channels used for specific purposes)\n Options are (case insensitive, only the first char counts):\ninfo - information\nm- main chat\n a - announcements\ns - staff chat\nl - logging chat\nr - rules.");
                    break;
                }
                try{
                    String name = "data/channels/"+event.getGuild().getId()+".csv";
                    Scanner scanner = new Scanner(Paths.get(name));
                    String[] options = scanner.nextLine().split(",");
                    HashMap<Character, Integer> eventMap = new HashMap<>(){{put('m', 0); put('a', 1); put('s', 2); put('l', 3); put('r', 4);}};
                    try{
                        options[eventMap.get(type.charAt(0))] = event.getOption("channel").getAsString();
                    }catch(Exception e){
                        event.reply("Wrong argument!").queue();
                        break;
                    }
                    PrintWriter writer = new PrintWriter(name);
                    writer.print(String.join(",", options));
                    writer.close();
                    logger.info(String.format("User %s changed property %s in server %s", event.getMember().getId(), eventMap.get(type.charAt(0)), event.getGuild().getName()));
                    event.reply("The change has been successfully applied.").queue();
                }catch(Exception e){
                    logger.error(String.format("User %s encountered an unexpected ERROR while trying to change property %s in server %s", event.getMember().getId(), event.getOption("channel").getAsString(), event.getGuild().getName()));
                    event.reply("An unexpected error has occurred. Contact the developer.").setEphemeral(true).queue();
                }
                break;
            case "kick":
                if(!event.getMember().hasPermission(Permission.KICK_MEMBERS)){
                    insufficientPermissionsStandardResponseSlashCommand(event);
                    break;
                }
                User kicked = event.getOption("kicked").getAsUser();
                String kickReason = (event.getOption("reason") != null ? event.getOption("reason").getAsString() : "");
                try{
                    event.getGuild().kick(event.getOption("kicked").getAsUser()).reason(kickReason).queue();
                    logger.info(String.format("User %s was kicked by %s in server %s. "+(kickReason.equals("") ? "No reason provided." : "Reason: %s"), kicked.getId(), event.getMember().getId(), event.getGuild().getName(), kickReason));
                    event.reply("Kick successful.").setEphemeral(true).queue();
                }catch(Exception e){
                    logger.error(String.format("User %s encountered an unexpected ERROR while trying to ban %s in server %s. ", event.getMember().getId(), kicked.getId(), event.getGuild().getName()));
                    e.printStackTrace();
                    event.reply("An unexpected error has occurred.").setEphemeral(true).queue();
                }
                if(getSpecialSetting(2, event.getGuild()) == 1){
                    privateMessage(kicked, "You have been kicked from "+event.getGuild().getName()+" by <@"+event.getMember().getId()+(kickReason.equals("") ? ">. No reason was provided" : (">. Reason:"+kickReason)));
                }
                break;
            case "ban":
                if(!event.getMember().hasPermission(Permission.BAN_MEMBERS)){
                    insufficientPermissionsStandardResponseSlashCommand(event);
                    break;
                }
                int deletionLength = (event.getOption("deletiontime") != null ? Math.min(MESSAGE_DELETION_LENGTH, event.getOption("deletiontime").getAsInt()) : MESSAGE_DELETION_LENGTH);
                User user = event.getOption("banned").getAsUser();
                if(user.getId().equals(event.getMember().getId())){
                    event.reply("You cannot ban yourself.").queue();
                    logger.warn(String.format("User %s attempted to execute an illegal command.", event.getMember().getId()));
                    break;
                }
                String reason = (event.getOption("reason") != null ? event.getOption("reason").getAsString() : "");
                event.deferReply();
                String loggedInfo = user.getId()+","+reason+","+(System.currentTimeMillis()/1000);
                try{
                    PrintWriter writer = new PrintWriter(new FileWriter(Paths.get("data/banList/"+event.getGuild().getId()+".csv").toFile(), true));
                    writer.println(loggedInfo);
                    writer.close();
                    if(getSpecialSetting(1, event.getGuild()) == 1){
                        specialMessage(0, event.getGuild(), "The user <@"+user.getId()+"> has been banned from "+event.getGuild().getName()+".");
                    }
                    if(getSpecialSetting(2, event.getGuild()) == 1){
                        privateMessage(user, "You have been banned from "+event.getGuild().getName()+" by <@"+event.getMember().getId()+(reason.equals("") ? ">. No reason was provided" : (">. Reason:"+reason)));
                    }
                    event.getGuild().ban(user, deletionLength, TimeUnit.HOURS).reason(reason).queue();
                    logger.info(String.format("User %s was banned by %s in server %s. "+(reason.equals("") ? "No reason provided." : "Reason: %s"), user.getId(), event.getMember().getId(), event.getGuild().getName(), reason));
                    event.reply("Ban successful.").setEphemeral(true).queue();
                }catch(Exception e){
                    //e.printStackTrace();
                    reloadFiles(event.getGuild());
                    logger.error(String.format("User %s encountered an unexpected ERROR while trying to ban %s in server %s. ", event.getMember().getId(), user.getId(), event.getGuild().getName()));
                    event.reply("An unexpected error has occurred.").queue();
                }
                break;
            case "unban":
                net.dv8tion.jda.api.entities.User unbanUser = event.getOption("banned").getAsUser();
                if(!event.getMember().hasPermission(Permission.BAN_MEMBERS)){
                    insufficientPermissionsStandardResponseSlashCommand(event);
                    break;
                }
                event.deferReply();
                unban(unbanUser, event.getGuild());
                try{
                    Scanner scanner = new Scanner(Paths.get("data/banList/"+event.getGuild().getId()+".csv"));
                    ArrayList<String> lines = new ArrayList<>();
                    while(scanner.hasNextLine()){
                        String next = scanner.nextLine();
                        if(!(next.contains(event.getOption("banned").getAsUser().getId()))){
                            lines.add(next);
                        }
                    }
                    scanner.close();
                    PrintWriter writer = new PrintWriter(Paths.get("data/banList/"+event.getGuild().getId()+".csv").toFile());
                    for(String i : lines){
                        writer.println(i);
                    }
                    writer.close();
                    logger.info(String.format("User %s was unbanned by %s in server %s. ", event.getOption("banned").getAsUser().getId(), event.getMember().getId(), event.getGuild().getName()));
                    event.reply("Unban successful.").setEphemeral(true).queue();
                }catch(Exception ignored){
                    logger.error(String.format("User %s encountered an unexpected ERROR while trying to unban %s in server %s.", event.getOption("banned").getAsUser().getId(), event.getMember().getId(), event.getGuild().getName()));
                    event.reply("An unexpected error has occurred.").queue();
                }
                break;
            case "banappealset":
                if(!event.getMember().hasPermission(Permission.BAN_MEMBERS)){
                    insufficientPermissionsStandardResponseSlashCommand(event);
                }
                event.deferReply();
                final String NAME = "data/banSettings/"+event.getGuild().getId()+".csv";
                int duration = Objects.requireNonNull(event.getOption("days")).getAsInt();
                if(Math.abs(duration-183) > 183){
                    event.reply("Erroneous duration - "+duration+" is not a valid duration. Input a positive number below 367.").setEphemeral(true).queue();
                    break;
                }
                try{
                    Scanner scanner = new Scanner(Paths.get(NAME));
                    String[] settings = scanner.nextLine().split(",");
                    duration *= 86400;
                    settings[0] = duration+"";
                    PrintWriter writer = new PrintWriter(NAME);
                    writer.println(settings[0]+","+settings[1]+","+settings[2]);
                    writer.close();
                }catch(Exception ignored){}
                logger.info(String.format("User %s changed ban appeal time to %s in server %s. ", event.getMember().getId(), duration+"", event.getGuild().getName()));
                event.reply("The change has been successfully applied.").queue();
                break;
            case "reload":
                if(!event.getMember().hasPermission(Permission.MANAGE_SERVER, Permission.MANAGE_CHANNEL)){
                    insufficientPermissionsStandardResponseSlashCommand(event);
                }
                reloadFiles(event.getGuild());
                logger.info(String.format("User %s reloaded data files in server %s. ", event.getMember().getId(), event.getGuild().getName()));
                event.reply("Successfully reloaded!").queue();
                break;
            case "banmessageset":
                if(!event.getMember().hasPermission(Permission.BAN_MEMBERS)){
                    insufficientPermissionsStandardResponseSlashCommand(event);
                    break;
                }
                event.deferReply();
                try{
                    final String NAME2 = "data/banSettings/"+event.getGuild().getId()+".csv";
                    Scanner scan = new Scanner(Paths.get(NAME2));
                    String setting = scan.nextLine();
                    int val1 = boolToInt(event.getOption("main").getAsBoolean());
                    int val2 = boolToInt(event.getOption("dm").getAsBoolean());
                    setting = setting.split(",")[0] + "," + val1 + "," + val2;
                   PrintWriter writer = new PrintWriter(NAME2);
                   writer.println(setting);
                   writer.close();
                }catch(Exception ignored){}
                event.reply("The change has been successfully applied.").queue();
                logger.info(String.format("User %s changed ban message properties in server %s.", event.getMember().getId(), event.getGuild().getName()));
                break;
        }
    }
    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event){
        switch(event.getName()){
            case "set":
                if(event.getFocusedOption().getName().equals("type")){
                    String[] options = {"info", "m", "a", "s", "l", "r"};
                    ArrayList<Command.Choice> optionsList = (ArrayList<Command.Choice>) Arrays.asList(options).stream().map(word -> new Command.Choice(word, word)).collect(Collectors.toList());
                    event.replyChoices(optionsList).queue();
                }
        }
    }
    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event){
        specialMessage(0, event.getGuild(), "<@"+event.getMember().getId()+"> Welcome!");
        logger.info(String.format("User %s joined server %s.", event.getMember().getId(), event.getGuild().getName()));
    }
    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event){
        ErrorHandler handler = new ErrorHandler().handle(ErrorResponse.UNKNOWN_BAN, (error) -> guildMemberRemoveFunc(event));
        event.getGuild().retrieveBan(event.getUser()).queue(null, handler);
    }
    public static void unban(User user, Guild guild){
        guild.unban(user).queue();
        if(getSpecialSetting(2, guild) == 1){
            privateMessage(user, ("You have been unbanned from " + guild.getName() + ". Welcome back!"));
        }
    }
    public static void guildMemberRemoveFunc(GuildMemberRemoveEvent event){
        specialMessage(0, event.getGuild(), "<@" + event.getUser().getId() + "> has left our server. We hope to see you again.");
        logger.info(String.format("User %s left server %s.", event.getUser().getId(), event.getGuild().getName()));
    }
    public static void scheduledStatusChanger(){
        try{
            String[] text = (new Scanner(Paths.get("data/Activities.txt")).useDelimiter("\\A").next().split("WATCHING"));
            String[][] array = {text[0].split("\n"), text[1].split("\n")};
            Random rand = new Random();
            int n = 0;
            while(n == 0 || (n >= array[0].length ? (array[1][n - array[0].length]) : (array[0][n])).equals("")){
                n = rand.nextInt(array[0].length + array[1].length);
                //System.out.println((n >= array[0].length ? (array[1][n - array[0].length]) : (array[0][n])));
            }
            Activity activity = (n >= array[0].length ? Activity.watching(array[1][n - array[0].length]) : Activity.playing(array[0][n]));
            jda.getPresence().setActivity(activity);
            logger.info(String.format("Status changed to %s.", (activity.getType() == Activity.ActivityType.PLAYING ? "playing" : "watching") + String.format(" %s", activity.getName())));
            scheduler.schedule(Bot::scheduledStatusChanger, STATUS_CHANGE_DURATION, TimeUnit.SECONDS);
        }catch(Exception e){
            e.printStackTrace();
        }
    }
    public static void automaticAppealer(){
        try{
            DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get("data/appeals"));
            for(Path i : stream){
                String name = i.getFileName().toString();
                Scanner scan = new Scanner(i);
                ArrayList<String> appealed = new ArrayList<>();
                while(scan.hasNextLine()){
                    Guild guild = jda.getGuildById(name.substring(0, name.length()-4));
                    String text = scan.nextLine();
                    if(text.equals("")){
                        continue;
                    }
                    String[] content = text.split(",");
                    if(Integer.parseInt(content[1]) <= curtime()){
                        appealed.add(text);
                        //System.out.println(text);
                        //System.out.println(name.substring(0, name.length()-4));
                        getSpecialChannel(2, guild).getHistory().retrievePast(100).queue(n -> {
                            for(Message j : n){
                                if(j.getId().equals(content[0])){
                                    List<MessageReaction> reactions = j.getReactions();
                                    //System.out.println(reactions.size());
                                    int[] votes = {0, 0};
                                    for(MessageReaction k : reactions){
                                        String emoji = k.getEmoji().getFormatted();
                                        //System.out.println(emoji); // below unicode codepoint is a thumbs-up emoji
                                        if(emoji.equals("\uD83D\uDC4D")){
                                            votes[0] = k.getCount();
                                        }else if(emoji.equals("\uD83D\uDC4E")){
                                            votes[1] = k.getCount();
                                        }else{
                                            continue;
                                        }
                                    }
                                    boolean isWon = (votes[0] > votes[1]);
                                    if(votes[0]+votes[1] < 5){
                                        specialMessage(2, guild, String.format("Not enough votes. \n<@%s> not unbanned.", content[2]));
                                    }else{
                                        specialMessage(2, guild, String.format("Appeal %ssuccessful.\n<@%s> %s unbanned.", (isWon ? "" : "un"), content[2], (isWon ? "has been" : "will not be")));
                                    }
                                    if(isWon){
                                        try{
                                            jda.retrieveUserById(content[2]).queue(u -> {
                                                unban(u, guild);
                                            });
                                        }catch(Exception ignored){}
                                        logger.info(String.format("User %s was unbanned by appeal in server %s.", content[2], content[0]));
                                    }else{
                                        logger.info(String.format("Ban appeal of user %s unsuccessful in server %s.", content[2], content[0]));
                                    }
                                }
                            }
                        });
                    }
                }
                scan.close();
                Scanner scan2 = new Scanner(i);
                StringBuilder newContent = new StringBuilder();
                while(scan2.hasNextLine()){
                    String text = scan2.nextLine();
                    if(!(appealed.contains(text))){
                        newContent.append(text).append("\n");
                    }
                }
                PrintWriter w = new PrintWriter(new FileWriter(i.toFile(), false));
                if(!newContent.isEmpty()){
                    w.println(newContent.substring(0, newContent.length() - 2));
                }
                w.close();
            }
        }catch(Exception e){
            e.printStackTrace();
            logger.info("Unexpected ERROR encountered while trying to process appeals.");
        }
        try{
            DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get("data/banList"));
            for(Path i : stream){
                String name = i.getFileName().toString();
                Scanner settingsScanner = new Scanner(Paths.get("data/banSettings/"+name));
                int appealTime = Integer.parseInt(settingsScanner.nextLine().split(",")[0]);
                //System.out.println(appealTime);
                Scanner scanner = new Scanner(i);
                ArrayList<String> toDelete = new ArrayList<>();
                while(scanner.hasNextLine()){
                    String text = scanner.nextLine();
                    if(text.equals("")){
                        continue;
                    }
                    String[] contents = text.split(",");
                    if(curtime() >= Integer.parseInt(contents[2])+appealTime){
                        try{
                            jda.retrieveUserById(contents[0]).queue(u -> {
                                jda.getGuildById(name.substring(0, name.length()-4)).retrieveBan(u).queue(
                                    (success) -> {
                                        System.out.println(contents[0]);
                                        toDelete.add(contents[0]);
                                        String message = String.format("Automatic Appeal\nUser <@%s> was banned %d days ago%s. Vote for appeal:", contents[0], appealTime/3600, (contents[1].equals("") ? "" : " for "+contents[1]));
                                        getSpecialChannel(2, jda.getGuildById(name.substring(0, name.length()-4))).sendMessage(message).queue((m) -> {
                                            try{
                                                String messageId = m.getId();
                                                m.addReaction(Emoji.fromUnicode("U+1F44D")).queue();
                                                m.addReaction(Emoji.fromUnicode("U+1F44E")).queue();
                                                PrintWriter v = new PrintWriter(new FileWriter(Paths.get("data/appeals/" + name).toFile(), true));
                                                v.println(messageId+(","+(curtime()+86375))+","+contents[0]);
                                                v.close();
                                                logger.info(String.format("Ban appeal of user %s started in server %s.", contents[0], name.substring(0, name.length()-4)));
                                                scanner.close();
                                                StringBuilder tobeprinted = new StringBuilder();
                                                Scanner scanner2 = new Scanner(i);
                                                while(scanner2.hasNextLine()){
                                                    String text5 = scanner2.nextLine();
                                                    if(text.equals("")){
                                                        continue;
                                                    }
                                                    //System.out.println(text);
                                                    if(!(toDelete.contains(text5.split(",")[0]))){
                                                        tobeprinted.append(text5).append("\n");
                                                    }
                                                }
                                                //System.out.println(tobeprinted);
                                                PrintWriter w = new PrintWriter(new FileWriter(Paths.get("data/banList/"+name).toFile(), false));
                                                w.println(tobeprinted);
                                                w.close();
                                            }catch(Exception e){
                                                e.printStackTrace();
                                            }
                                        });
                                    },
                                    (failure) -> {
                                        // to be done - deleting unnecessary banlist entries here
                                    }
                                );
                            });
                        }catch(Exception ignored){}
                    }
                }
            }
        }catch(Exception ignored){
            ignored.printStackTrace();
        }
    }
    public static void insufficientPermissionsStandardResponseSlashCommand(SlashCommandInteractionEvent event){
        logger.warn(String.format("User %s attempted to execute an illegal command in server %s.", event.getMember().getId(), event.getGuild().getId()));
        event.reply("Insufficient permissions!").queue();
    }
    public static TextChannel getSpecialChannel(int index, Guild guild){
        try{
            return guild.getTextChannelById(new Scanner(Paths.get("data/channels/" + guild.getId() + ".csv")).nextLine().split(",")[index]);
        }catch(Exception e){
            e.printStackTrace();
            return null;
        }
    }
    public static int getSpecialSetting(int index, Guild guild){
        try{
            return Integer.parseInt(new Scanner(Paths.get("data/banSettings/" + guild.getId() + ".csv")).nextLine().split(",")[index]);
        }catch(Exception e){
            return -1;
        }
    }
    public static int boolToInt(boolean bool){
        return Boolean.compare(bool, false);
    }
    public static void privateMessage(User user, String message){
        // user.openPrivateChannel().flatMap(channel -> channel.sendMessage(message)).queue(); currently broken
        logger.debug("Private message was used. The private messaging function appears to be broken.");
    }
    public static void specialMessage(int channel, Guild guild, String message){
        getSpecialChannel(channel, guild).sendMessage(message).queue();
        // 0 - main chat, 1 - announcement chat, 2 - staff chat, 3 - logging chat, 4 - rules chat
    }
    public static int processDuration(String duration){
        HashMap<Character, Integer> eventMap = new HashMap<>(){{put('s', 1); put('m', 60); put('h', 3600); put('d', 86400);}};
        return (int) (Double.parseDouble(duration.substring(0, duration.length()-1))*eventMap.get(duration.charAt(duration.length()-1)));
    }
    public static void reloadFile(Guild guild, String dir, int zeroes){
        try{
            String name = "data/"+dir+"/"+guild.getId()+".csv";
            boolean flag = new File(name).createNewFile();
            if(flag && zeroes!=0){
                PrintWriter writer = new PrintWriter(name);
                StringBuilder builder = new StringBuilder();
                for(int i = 0; i < zeroes; i++){
                    builder.append((i == zeroes-1 ? "0" : "0,"));
                }
                writer.println(builder);
                writer.close();
            }
        }catch(Exception ignored){}
    }
    public static void reloadFiles(Guild guild){
        reloadFile(guild, "channels", 5);
        // 0 - main chat, 1 - announcement chat, 2 - staff chat, 3 - logging chat, 4 - rules chat
        reloadFile(guild, "banSettings", 3);
        // 0 - number of hours, 1 - announcement in main chat, 2 - DM to banned user
        reloadFile(guild, "banList", 0);
        reloadFile(guild, "appeals", 0);
    }
}
