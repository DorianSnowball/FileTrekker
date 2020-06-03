package de.dhbw.trekker;

import org.apache.commons.cli.*;

import java.util.Arrays;

public class Main {

    public static void main(String[] args) {

        Options options = new Options();

        Option path = new Option("d", "directory", true,"path to the directory with the community repos");
        path.setRequired(true);
        options.addOption(path);

        Option mode = new Option("m", "mode", true, "Specifiy the mode what FileTrekker shall do: "+ Arrays.toString(Trekker.Mode.values()));
        mode.setRequired(true);
        options.addOption(mode);

        Option regex = new Option("r", "regex", true, "regex to search in files");
        options.addOption(regex);

        Option linebreaks = new Option("l", "no-linebreaks", false, "Read files without linebreak");
        options.addOption(linebreaks);

        Option minN = new Option("n", "min",true, "Specifiy minimal N for printing");
        options.addOption(minN);

        // Maybe add Option to specify comma or semicolon

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            formatter.printHelp("FileTrekker knows the following arguments. Some arguments are optional depending on the selected mode", options);
            System.exit(1);
        }

        int n = 0;
        try {
            n = Integer.parseInt(cmd.getOptionValue("n", "0"));
        } catch (NumberFormatException e) {
            System.err.println("Invalid Option for argument n! Please provide a valid integer!");
            System.exit(2);
        }

        Trekker.Mode trekkerMode = null;
        try {
            trekkerMode = Trekker.Mode.valueOf(cmd.getOptionValue("m").toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid Option for argument m! Please provide a valid mode!");
            System.exit(2);
        }


        new Trekker(cmd.getOptionValue("directory"), trekkerMode , cmd.getOptionValue("regex", ""), cmd.hasOption("no-linebreaks"), n);
        // get path of repos directory -> required
        // get arguments like no-linebreaks
        // get regex -> required

    }

}
