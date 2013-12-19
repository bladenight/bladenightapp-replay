package de.greencity.bladenightapp.replay;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import de.greencity.bladenightapp.events.Event;
import de.greencity.bladenightapp.events.Event.EventStatus;
import de.greencity.bladenightapp.events.EventList;
import de.greencity.bladenightapp.persistence.InconsistencyException;
import de.greencity.bladenightapp.persistence.ListPersistor;
import de.greencity.bladenightapp.replay.log.LogEntryHandler;
import de.greencity.bladenightapp.replay.log.LogFilePlayer;
import de.greencity.bladenightapp.replay.log.ParticipanLogFile;
import de.greencity.bladenightapp.replay.log.local.LogEntryHandlerProcession;
import de.greencity.bladenightapp.replay.log.wamp.LogEntryHandlerWampClient;
import de.greencity.bladenightapp.replay.speedgen.SpeedControlledPlayer;
import de.greencity.bladenightapp.routes.Route;
import de.greencity.bladenightapp.routes.RouteStore;

public class Main {

	public static void main(String[] args) throws Exception {
		commandLine = parseCommandLine(args);

		if ( commandLine.getOptionValue("file") != null ) {
			runLogFilePlayer();
		}
		else if ( commandLine.getOptionValue("speed") != null ) {
			runConstantSpeedPlayer();
		}

		// TODO investigate why the program doesn't terminate
		System.exit(0);
	}


	private static void runLogFilePlayer()
			throws URISyntaxException, IOException, InterruptedException, InconsistencyException {
		
		String urlOption = commandLine.getOptionValue("url");
		String eventsDirOption = commandLine.getOptionValue("events-dir");
		if ( urlOption != null ) {
			runLogFilePlayerWithWampClient(urlOption);
		}
		else if ( eventsDirOption != null ) {
			runLogFilePlayerLocalForEveryEvent();
		}
		else {
			runLogFilePlayerLocal();
		}

	}
	
	private static LogFilePlayer createPlayerFromOptions(LogEntryHandler logEntryHandler) throws IOException {
		LogFilePlayer player = new LogFilePlayer(logEntryHandler);
		
		player.readLogEntries(new File(commandLine.getOptionValue("file")));

		if (commandLine.getOptionValue("fromtime") != null)
			player.setFromDateTime(parseCommandLineDateString(commandLine.getOptionValue("fromtime")));
		if (commandLine.getOptionValue("totime") != null)
			player.setToDateTime(parseCommandLineDateString(commandLine.getOptionValue("totime")));
		if (commandLine.getOptionValue("timelapse") != null)
			player.setTimeLapseFactor(Double.parseDouble(commandLine.getOptionValue("timelapse")));
		
		return player;
	}
	
	private static void runLogFilePlayerWithWampClient(String url) throws URISyntaxException, IOException, InterruptedException {
		LogEntryHandler logEntryHandler;
		logEntryHandler = new LogEntryHandlerWampClient(new URI(url));
		LogFilePlayer player = createPlayerFromOptions(logEntryHandler);
		player.replay();
	}

	private static void runLogFilePlayerLocalForEveryEvent() throws IOException, InconsistencyException, InterruptedException {
		String eventsDirOption = commandLine.getOptionValue("events-dir");
		String routesDirOption = commandLine.getOptionValue("routes-dir");
		EventList eventList = new EventList();
		ListPersistor<Event> persistor = new ListPersistor<Event>(Event.class);
		persistor.setDirectory(new File(eventsDirOption));
		eventList.setPersistor(persistor);
		eventList.read();
		RouteStore routeStore = new RouteStore(new File(routesDirOption));
		ParticipanLogFile logFile = new ParticipanLogFile(new File(commandLine.getOptionValue("file")));
		getLog().info("Reading log file...");
		logFile.load();
		List<ParticipanLogFile.LogEntry> logEntries = logFile.getEntries();
		for(Event event : eventList) {
			System.out.println(event);
			if ( event.getStatus() != EventStatus.CONFIRMED )
				continue;
			String prefix = event.getStartDate().toString("yyyy-MM-dd");
			Route route = routeStore.getRoute(event.getRouteName());
			LogEntryHandlerProcession logEntryHandler = new LogEntryHandlerProcession(prefix, route, event);
			LogFilePlayer player = new LogFilePlayer(logEntryHandler);
			player.setFromDateTime(event.getStartDate());
			player.setToDateTime(event.getEndDate());
			if (commandLine.getOptionValue("timelapse") != null)
				player.setTimeLapseFactor(Double.parseDouble(commandLine.getOptionValue("timelapse")));
			player.setLogEntries(logEntries);
			player.replay();
		}
	}

	private static void runLogFilePlayerLocal() throws InterruptedException, IOException {
		Route route = new Route();
		String routeFile = commandLine.getOptionValue("route");
		if ( ! route.load(new File(routeFile)) ) {
			getLog().error("Failed to load route: " + routeFile);
			System.exit(1);
		}
		String prefix = "log";
		if (commandLine.getOptionValue("fromtime") != null)
			prefix = parseCommandLineDateString(commandLine.getOptionValue("fromtime")).toString("yyyy-MM-dd");
		getLog().info("Route length:" + route.getLength());
		LogEntryHandler logEntryHandler = new LogEntryHandlerProcession(prefix, route, null);
		LogFilePlayer player = createPlayerFromOptions(logEntryHandler);
		player.replay();
	}

	private static void runConstantSpeedPlayer() throws URISyntaxException {
		SpeedControlledPlayer player = new SpeedControlledPlayer(new URI(commandLine.getOptionValue("url")));

		if (commandLine.getOptionValue("speed") != null)
			player.setBaseSpeed(Double.parseDouble(commandLine.getOptionValue("speed")));
		if (commandLine.getOptionValue("count") != null)
			player.setParticipantCount(Integer.parseInt(commandLine.getOptionValue("count")));
		if (commandLine.getOptionValue("startperiod") != null)
			player.setStartPeriod(Integer.parseInt(commandLine.getOptionValue("startperiod")));

		player.play();
	}

	@SuppressWarnings("static-access")
	private static CommandLine parseCommandLine(String[] args) {
		// create the command line parser
		CommandLineParser parser = new PosixParser();

		// create the Options
		Options options = new Options();

		options.addOption(OptionBuilder
				.withLongOpt( "url" )
				.withDescription( "server url")
				.hasArg()
				.withArgName("URL")
				.create() );

		options.addOption(OptionBuilder
				.withLongOpt( "file" )
				.withDescription( "input log file")
				.hasArg()
				.withArgName("LOGFILE")
				.create() );
		options.addOption(OptionBuilder
				.withLongOpt( "events-dir" )
				.withDescription( "events directory")
				.hasArg()
				.withArgName("EVENTSDIR")
				.create() );
		options.addOption(OptionBuilder
				.withLongOpt( "routes-dir" )
				.withDescription( "routes directory")
				.hasArg()
				.withArgName("EVENTSDIR")
				.create() );
		options.addOption(OptionBuilder
				.withLongOpt( "route" )
				.withDescription( "route file (kml)")
				.hasArg()
				.withArgName("ROUTEFILE")
				.create() );
		options.addOption(OptionBuilder
				.withLongOpt( "fromtime" )
				.withDescription( "start time (\"yyyy-mm-ddThh:mm\"")
				.hasArg()
				.withArgName("STARTTIME")
				.create() );
		options.addOption(OptionBuilder
				.withLongOpt( "totime" )
				.withDescription( "to time (\"yyyy-mm-ddThh:mm\"")
				.hasArg()
				.withArgName("ENDTIME")
				.create() );
		options.addOption(OptionBuilder
				.withLongOpt( "timelapse" )
				.withDescription( "time lapse factor. 60 means for instance 1 computer sec = 1 bladenight minute ")
				.hasArg()
				.withArgName("TIMELAPSE")
				.create() );

		options.addOption(OptionBuilder
				.withLongOpt( "speed" )
				.withDescription( "speed in km/h")
				.hasArg()
				.withArgName("SPEED")
				.create() );
		options.addOption(OptionBuilder
				.withLongOpt( "count" )
				.withDescription( "number of participants to simulate")
				.hasArg()
				.withArgName("COUNT")
				.create() );
		options.addOption(OptionBuilder
				.withLongOpt( "startperiod" )
				.withDescription( "wait time between participant starts")
				.hasArg()
				.withArgName("STARTPERIOD")
				.create() );

		CommandLine commandLine = null;

		try {
			commandLine = parser.parse( options, args );
		}
		catch( ParseException exp ) {
			getLog().error(exp.getMessage());
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp( "bladenightapp-replay", options );
			System.exit(1);
		}
		return commandLine;
	}


	private static DateTime parseCommandLineDateString(String dateString)  {
		DateTimeFormatter dateFormatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm");
		return dateFormatter.parseDateTime(dateString);
	}
	
	final String DEFAULT_URL = "ws://localhost:8081";
	
	private static Log log;

	public static void setLog(Log log) {
		Main.log = log;
	}

	protected static Log getLog() {
		if (log == null)
			setLog(LogFactory.getLog(Main.class));
		return log;
	}
	
	static private CommandLine commandLine;
}
