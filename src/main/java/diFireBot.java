import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.telegram.telegrambots.ApiContextInitializer;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class diFireBot {

    static {
        ApiContextInitializer.init();
    }

    private static boolean isApplicationRun = true;

    public static void main(String[] args) {
        final var bot = new TelegramBotImpl(
                new FileSystemDataBase()
        );
        var tgBotThread = new Thread(() -> {
            try {
                new TelegramBotsApi()
                        .registerBot(bot);
            } catch (TelegramApiRequestException e) {
                e.printStackTrace();
                isApplicationRun = false;
            }
        });
        tgBotThread.setName("Bot web hook server runner thread");
        tgBotThread.setDaemon(true);
        tgBotThread.start();
        try (final var serverSocket = new ServerSocket(8089)) {
            while (isApplicationRun) {
                try (final var clientSocket = serverSocket.accept()) {
                    final var writer =
                            new BufferedWriter(
                                    new OutputStreamWriter(clientSocket.getOutputStream()));
                    writer.flush();
                    isApplicationRun = false;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("Finish");
    }

    private static class TelegramBotImpl extends TelegramLongPollingBot {

        private final ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();

        private final Map<String, Steps> userSessionMap;

        private final List<String> courseList;

        private final List<String> admins;

        private final FileSystemDataBase db;

        private final ObjectMapper objectMapper;

        public TelegramBotImpl(final FileSystemDataBase db) {
            this.userSessionMap = new HashMap<>();
            this.courseList = this.getCourses();
            this.admins = this.getAdmins();
            this.db = db;
            this.objectMapper = new ObjectMapper();
            this.init();
        }

        private void init() {
            this.db.createRootFolder();
        }

        private List<String> getCourses() {
            final List<String> courses = new ArrayList<>();
            courses.add("smm-больше, чем инстаграм");
            courses.add("курс мобильной фотографии");
            return courses;
        }

        private List<String> getAdmins() {
            final List<String> administrators = new ArrayList<>();
            administrators.add("186164861");
            administrators.add("483858204");
            return administrators;
        }

        @Override
        public void onUpdateReceived(final Update update) {
            if (update != null) {
                final var consumeMessage = update.getMessage();
                final var consumeMessageText = consumeMessage.getText();
                final var userChatId = consumeMessage.getChatId().toString();
                SendMessage sendMessage = null;
                if (this.courseList.contains(consumeMessageText.toLowerCase()) || !consumeMessageText.isEmpty()) {
                    sendMessage = new SendMessage()
                            .setChatId(userChatId)
                            .setText(this.handleAndGenerateResponseMessage(userChatId, consumeMessageText))
                            .setReplyMarkup(this.replyKeyboardMarkup)
                            .enableMarkdown(true);
                } else return;
                try {
                    this.execute(sendMessage);
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
            }
        }

        public String getBotUsername() {
            return "DiFireBot";
        }

        public String getBotToken() {
            return "850867252:AAF-fWbZmqt-VPygH5nf0HIdPX7lCRBunuk";
        }

        private String handleAndGenerateResponseMessage(final String userChatId, final String consumeMessageText) {
            var currentState = userSessionMap.get(userChatId);
            final var keyboard = new ArrayList<KeyboardRow>();
            final var keyboardRow = new KeyboardRow();

            replyKeyboardMarkup.setSelective(true);
            replyKeyboardMarkup.setResizeKeyboard(true);
            replyKeyboardMarkup.setOneTimeKeyboard(true);
            if (this.admins.contains(userChatId)) {
                if (consumeMessageText.equals("/users")) {
                    return this.getUsers();
                }
            }
            if (consumeMessageText.equals("/start")) {
                if (this.getUserChatIds().contains(userChatId)) {
                    return "Вы уже зарегистрированы";
                }
                userSessionMap.put(userChatId, Steps.START);

                this.courseList.forEach(keyboardRow::add);
                keyboard.add(keyboardRow);
                replyKeyboardMarkup.setKeyboard(keyboard);

                return "Здравствуйте, на какой курс вы хотите записаться? \n" +
                        "> SMM-больше, чем Инстаграм \n" +
                        "> Курс мобильной фотографи";

            }
            switch (currentState) {
                case START: {
                    if (this.courseList.contains(consumeMessageText)) {
                        try {
                            this.userSessionMap.put(userChatId, Steps.COURSE);
                            new SendMessage().setReplyMarkup(new ReplyKeyboardMarkup()).enableMarkdown(false);
                            final var dbTemplate = new DBTemplate();
                            dbTemplate.setUserChatId(userChatId);
                            dbTemplate.setCourse(consumeMessageText);
                            final var dbTemplateString = this.objectMapper.writeValueAsString(dbTemplate);
                            db.createNewFileWidthData(dbTemplateString, Paths.FULL_DB_PATH + "/" + userChatId);
                            return "Введите Ф.И.О.";
                        } catch (IOException e) {
                            e.printStackTrace();
                            throw new RuntimeException("Trouble width serialization");
                        }

                    }
                }
                case COURSE: {
                    final var fullName = consumeMessageText.split(" ");
                    if (fullName.length < 2) {
                        return "Неверные данные";
                    }
                    this.userSessionMap.put(userChatId, Steps.FULL_NAME);
                    this.updateDbTemplate(userChatId, consumeMessageText, Steps.COURSE);
                    return "Введите номер телефона";
                }
                case FULL_NAME: {
                    this.updateDbTemplate(userChatId, consumeMessageText, Steps.FINISH);
                    this.admins.forEach(admin -> {
                        try {
                            this.execute(new SendMessage()
                                    .setChatId(admin)
                                    .setText(this.getUser(userChatId)));
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                            throw new RuntimeException("loh pidr");
                        }
                    });
                    this.userSessionMap.remove(userChatId);
                    return "Вы зарегестрированы на курс";
                }
                case FINISH: {
                    return "Вы уже зарегестрированы на курс";
                }
                default:
                    throw new RuntimeException("Trouble, ..... big trouble");
            }
        }

        private String getUsers() {
            final var responseMessage = new StringBuilder();
            Stream.of(Objects.requireNonNull(new File(Paths.FULL_DB_PATH).listFiles()))
                    .map(file -> TelegramBotImpl.this.db.getFileData(file.toPath().toString()))
                    .map(data -> {
                        try {
                            return TelegramBotImpl.this.objectMapper.readValue(data, DBTemplate.class);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e.getMessage());
                        }
                    })
                    .forEach(data -> {
                        responseMessage.append(data.fullName);
                        responseMessage.append("\n");
                        responseMessage.append(data.phoneNumber);
                        responseMessage.append("\n");
                        responseMessage.append(data.course);
                        responseMessage.append("\n");
                        responseMessage.append(data.registrationDate);
                        responseMessage.append("\n");
                        responseMessage.append("\n");
                    });
            return responseMessage.toString();
        }

        private String getUser(String chatId) {
            final var responseMessage = new StringBuilder();
            Stream.of(Objects.requireNonNull(new File(Paths.FULL_DB_PATH).listFiles()))
                    .map(file -> TelegramBotImpl.this.db.getFileData(file.toPath().toString()))
                    .map(data -> {
                        try {
                            return TelegramBotImpl.this.objectMapper.readValue(data, DBTemplate.class);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e.getMessage());
                        }
                    })
                    .forEach(data -> {
                        if (data.userChatId.equals(chatId)) {
                            responseMessage.append(data.fullName);
                            responseMessage.append("\n");
                            responseMessage.append(data.phoneNumber);
                            responseMessage.append("\n");
                            responseMessage.append(data.course);
                            responseMessage.append("\n");
                            responseMessage.append(data.registrationDate);
                            responseMessage.append("\n");
                            responseMessage.append("\n");
                        }
                    });
            return responseMessage.toString();
        }

        private List<String> getUserChatIds() {
            return Stream.of(Objects.requireNonNull(new File(Paths.FULL_DB_PATH).listFiles()))
                    .map(File::getName)
                    .collect(Collectors.toList());
        }

        private void updateDbTemplate(final String userChatId, final String data, final Steps step) {
            try {
                this.userSessionMap.put(userChatId, Steps.FULL_NAME);
                final var dbFilePath = Paths.FULL_DB_PATH + "/" + userChatId;
                final var dbTemplateString = this.db.getFileData(dbFilePath);
                final var dbTemplate = this.objectMapper.readValue(dbTemplateString, DBTemplate.class);
                if (step == Steps.COURSE) {
                    dbTemplate.setFullName(data);
                } else if (step == Steps.FULL_NAME) {
                    Date date = new Date();
                    dbTemplate.setPhoneNumber(data);
                    dbTemplate.setRegistrationDate(date.toString());
                }
                final var newDbTemplateString = this.objectMapper.writeValueAsString(dbTemplate);
                this.db.updateFileData(new File(dbFilePath), newDbTemplateString);
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("Trouble width read serialization");
            }
        }
    }

    private static class FileSystemDataBase {

        public String getFileData(final String path) {
            try (final var fileInputStream = new FileInputStream(path)) {
                try (final var inputStreamReader = new InputStreamReader(fileInputStream, StandardCharsets.UTF_8)) {
                    try (final var bufferedReader = new BufferedReader(inputStreamReader)) {
                        return bufferedReader.readLine();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("Blyaaa");
            }
        }

        public void createNewFileWidthData(final String data, final String path) {
            try (final var fileOutputStream = new FileOutputStream(path)) {
                fileOutputStream.write(data.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("Trouble");
            }
        }

        public void updateFileData(final File editableFile, final String newData) {
            try (final var fileWriter = new FileWriter(editableFile)) {
                fileWriter.write(newData);
                fileWriter.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private File createRootFolder() {
            final var file = new File(Paths.DB_PATH + Paths.DB_PATH + "/");
            if (!file.exists()) {
                file.mkdirs();
                file.setWritable(true);
                file.setReadable(true);
            }
            return file;
        }
    }

    private enum Steps {
        START, COURSE, FULL_NAME, FINISH
    }

    private static class Paths {
        private static final String FULL_DB_PATH = "/home/ali/difire";
        private static final String DB_PATH = "/difire";
    }

    private static class DBTemplate {
        private String userChatId;
        private String course;
        private String fullName;
        private String phoneNumber;
        private String registrationDate;

        public DBTemplate() {

        }

        public DBTemplate(final String userChatId,
                          final String course,
                          final String fullName,
                          final String phoneNumber,
                          final String registrationDate) {
            this.userChatId = userChatId;
            this.course = course;
            this.fullName = fullName;
            this.phoneNumber = phoneNumber;
            this.registrationDate = registrationDate;
        }

        public String getUserChatId() {
            return userChatId;
        }

        public String getCourse() {
            return course;
        }

        public String getFullName() {
            return fullName;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public String getRegistrationDate() {
            return registrationDate;
        }

        public void setUserChatId(String userChatId) {
            this.userChatId = userChatId;
        }

        public void setCourse(String course) {
            this.course = course;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }

        public void setRegistrationDate(String registrationDate) {
            this.registrationDate = registrationDate;
        }
    }
}
