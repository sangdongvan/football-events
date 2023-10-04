package org.djar.football.tests;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;

public class DemoApplication {

    private static final Logger logger = LoggerFactory.getLogger(DemoApplication.class);

    private final DateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm");
    private final DateFormat sqlFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final FootballEcosystem fbApp;

    private double factor = 0.0001;
    private long maxDelay = 2000;
    private long minDelay = 1000;

    private Date firstReqTimestamp;

    public DemoApplication(FootballEcosystem fbApp) {
        this.fbApp = fbApp;
    }

    public void generateFrom(String fileName) throws Exception {
        try (LineNumberReader reader = reader(fileName)) {
            String[] nextLine = readLine(reader);
            Date nextReqTimestamp = isoFormat.parse(nextLine[0]);
            firstReqTimestamp = nextReqTimestamp;

            while (true) {
                String[] currentLine = nextLine;

                if (currentLine[1].startsWith("INSERT")) {
//                    sql(currentLine);
                } else {
                    rest(currentLine);
                }
                nextLine = readLine(reader);

                if (nextLine == null) {
                    return;
                }
                Date reqTimestamp = nextReqTimestamp;
                nextReqTimestamp = isoFormat.parse(nextLine[0]);

                wait(reqTimestamp, nextReqTimestamp);
            }
        }
    }

    private LineNumberReader reader(String fileName) {
        return new LineNumberReader(new InputStreamReader(getClass().getClassLoader().getResourceAsStream(fileName)));
    }

    private void wait(Date reqTimestamp, Date nextReqTimestamp) {
        long timeDiff = nextReqTimestamp.getTime() - reqTimestamp.getTime();
        long delay = (long)(timeDiff * factor);

        if (delay > 0) {
            if (delay < minDelay) {
                delay = minDelay;
            } else if (delay > maxDelay) {
                delay = maxDelay;
            }
        }
        logger.debug("{} ms delay...", delay);
        sleep(delay);
    }

    private void sleep(long delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private String[] readLine(LineNumberReader reader) throws IOException {
        String line = reader.readLine();

        if (line == null) {
            return null;
        }
        return line.split("\t");
    }

    private void rest(String[] line) {
        String httpMethod = line[1];
        String url = line[2];
        String body = line[3];
        body = applyParams(line, 4, body, isoFormat);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpStatus statusCode = null;

        for (int i = 0; i < 3; i++) {
            try {
                statusCode = fbApp.command(url, HttpMethod.valueOf(httpMethod), body);

                if (statusCode.is2xxSuccessful()) {
                    logger.debug("{} {} {} {}", statusCode, httpMethod, url, body);
                    return;
                }
                if (statusCode == HttpStatus.NOT_FOUND || statusCode == HttpStatus.UNPROCESSABLE_ENTITY) {
                    logger.warn("retry {} {} {} {}", statusCode, httpMethod, url, body);
                    sleep(2000);
                    continue;
                }
                throw new RuntimeException(statusCode + " " + httpMethod + " " + url + " " + body);
            } catch (RestClientException e) {
                throw new RuntimeException(httpMethod + " " + url + " " + body, e);
            }
        }
        throw new RuntimeException("Response status is still " + statusCode + " " + httpMethod + " " + url
                + " " + body);
    }

    private void sql(String[] line) {
        String sql = applyParams(line, 2, line[1], sqlFormat);

        try {
            fbApp.executeSql(sql);
            logger.debug("{}", sql);
        } catch (DataAccessException e) {
            throw new RuntimeException(sql, e);
        }
    }

    private String applyParams(String[] line, int fromIndex, String body, DateFormat dateFormat) {
        String result = body;
        int paramIndex = 0;

        for (int tokenIndex = fromIndex; tokenIndex < line.length; tokenIndex++) {
            try {
                Date date = dateFormat.parse(line[tokenIndex]);
                String param = dateFormat.format(calculate(date));
                result = result.replace("${" + paramIndex++ + "}", param);
            } catch (ParseException e) {
                throw new RuntimeException("Invalid record at " + line[0]);
            }
        }
        return result;
    }

    private Date calculate(Date date) {
        long duration = (long)((date.getTime() - firstReqTimestamp.getTime()) * factor);
        return new Date(System.currentTimeMillis() + duration);
    }

    private static void printUsage(FootballEcosystem fbApp, String srcFile) {
        System.out.println("Usage:");
        System.out.println("  java -jar football-tests-xxx [startup-timeout] [rest-timeout] [src-file]");
        System.out.println();
        System.out.println("  startup-timeout    Set max startup time for the whole application (default: "
                + fbApp.getStartupTimeout() / 1000 + " s)");
        System.out.println("  rest-timeout       Set max response time for REST commands (default: "
                + fbApp.getRestTimeout() / 1000 + " s)");
        System.out.println("  src-file           Specify an alternate source file (default: "
                + srcFile + ")");
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        FootballEcosystem fbApp = new FootballEcosystem();
        String srcFile = "EFL-Championship-2017-2018.txt";

        if (args.length == 0) {
            printUsage(fbApp, srcFile);
        } else {
            fbApp.setStartupTimeout(Integer.parseInt(args[0]) * 1000);

            if (args.length > 1) {
                fbApp.setRestTimeout(Integer.parseInt(args[1]) * 1000);
            }
            if (args.length > 2) {
                srcFile = args[2];
            }
        }
        fbApp.start();

        logger.info("*************************************************");
        logger.info("Dashboard is available at http://localhost:18080/");
        logger.info("*************************************************");

        DemoApplication gen = new DemoApplication(fbApp);
        gen.generateFrom(srcFile);

        // shutdown only if no exception
        fbApp.shutdown();
    }
}
