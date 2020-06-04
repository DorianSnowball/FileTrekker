package de.dhbw.trekker;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Trekker {
    private final String regex;
    private final boolean noLineBreaks;


    public Trekker(String directory, Mode trekkerMode, String regex, boolean noLineBreaks, int n) {
        this.regex = regex;
        this.noLineBreaks = noLineBreaks;

        Stream<Path> allFiles = getAllFiles(directory);

        switch (trekkerMode) {
            case COUNT -> {
                HashMap<Path, List<String>> founds = new HashMap<>();
                allFiles.forEach(path -> founds.put(path, findRegex(path)));
                countFiltersInSignatures(founds);
            }
            case COMBINATIONS -> {
                HashMap<Path, List<String>> founds = new HashMap<>();
                allFiles.forEach(path -> founds.put(path, findRegex(path)));
                countCombinations(founds, n);
            }
            case SIGNATURECHANGE, FILTERAGE -> analyseGitChanges(directory, allFiles, trekkerMode);
        }

    }

    private void analyseGitChanges(String directory, Stream<Path> files, Mode trekkerMode) {

        // Find and init the git repository
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        builder.findGitDir(new File(directory));

        // abort program if no directory was found
        if (builder.getGitDir() == null) {
            System.err.println("Critical Error: Couldn't find git directory above " + directory);
            System.exit(3);
        }

        // Open Repo
        Repository repo = null;
        try {
            repo = builder.build();
        } catch (IOException e) {
            System.err.println("Critical Error: Couldn't open git directory because " + e.getMessage());
            System.exit(4);
        }

        // Open Porcelain Git
        Git git = new Git(repo);

        // Convert Signature Filepaths (relative to CWD) to Filepaths relative to the repo
        // example: "..\repos\cuckoo-community\modules\signatures\" to "modules\signatures"
        Path gitDirParent = repo.getDirectory().toPath().getParent();

//        ArrayList<Path> relativeGitPaths = new ArrayList<>();
        HashMap<Path, Path> gitReltoCWDRelMap = new HashMap<>();
        files.forEach(path -> gitReltoCWDRelMap.put(gitDirParent.relativize(path), path));


        ArrayList<Path> unmodified = new ArrayList<>(gitReltoCWDRelMap.keySet());

        StringBuilder stringBuilder = new StringBuilder();
        if (trekkerMode.equals(Mode.SIGNATURECHANGE))
            stringBuilder.append("Signature;ID;Author;Message;FilesTouchedByCommit\n");
        if (trekkerMode.equals(Mode.FILTERAGE))
            stringBuilder.append("Filter;Time\n");
        HashMap<String, FilterAPI> apiMap = new HashMap<>();

        for (Path signatur : gitReltoCWDRelMap.keySet()) {

            // change path seperator from "\" (windows) to "/" (unix) so JGit can work with it
            try {
                // Get all Commits for this file
                Iterable<RevCommit> commits = git.log().addPath(signatur.toString().replace('\\', '/')).call();
                int count = 0;
                RevWalk revWalk = new RevWalk(repo);

                Instant oldestCommit = Instant.now();
                HashMap<Instant, List<List<String>>> filtersByCommitTime = new HashMap<>();

                for (RevCommit commit : commits) {
                    count++;
//                    revWalk.reset();

                    if (trekkerMode.equals(Mode.SIGNATURECHANGE)) {
                        try (ObjectReader reader = git.getRepository().newObjectReader()) {
                            commit = revWalk.parseCommit(commit.getId());

                            // Get TreeIterators of the commit and his parent
                            AbstractTreeIterator newTree = new CanonicalTreeParser(null, reader, commit.getTree());
                            AbstractTreeIterator oldTree;
                            if (commit.getParentCount() != 0) {
                                RevCommit parent = commit.getParent(0);
                                parent = revWalk.parseCommit(parent.getId());
                                oldTree = new CanonicalTreeParser(null, reader, parent.getTree());
                            } else {
                                oldTree = new EmptyTreeIterator();
                            }

                            // Create Diff between those two trees
                            List<DiffEntry> diffEntries = git.diff().setContextLines(0).setOldTree(oldTree).setNewTree(newTree).call();

                            // Check for multiple files in diff
                            Set<String> filePaths = new HashSet<>();

                            for (DiffEntry diffEntry : diffEntries) {
                                filePaths.add(diffEntry.getNewPath());
                            }

                            // Remove signature from unmodified list if multiple commits per signature and commit only touches this signature
                            if (count > 1) {
                                if (filePaths.size() == 1) {
                                    unmodified.remove(signatur);
                                }
                            }

                            stringBuilder.append(";").append(commit.getId().toString()).append(";")
                                    .append(commit.getAuthorIdent().getName()).append(";")
                                    .append(commit.getShortMessage()).append(";")
                                    .append(filePaths.size()).append(";")
                                    .append(unmodified.contains(signatur)).append("\n");
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                    } else if (trekkerMode.equals(Mode.FILTERAGE)) {

                        Instant time = Instant.ofEpochSecond(commit.getCommitTime());
                        if (time.isBefore(oldestCommit)) {
                            oldestCommit = time;
                        }
                        List<List<String>> filtersBySignature = new ArrayList<>();

                        List<String> regex = findRegex(gitReltoCWDRelMap.get(signatur));
                        for (String s : regex) {
                            filtersBySignature.add(extraktFiltersFromString(s));
                        }
                        filtersByCommitTime.put(time, filtersBySignature);
                    }
                }

                if (trekkerMode.equals(Mode.FILTERAGE)) {

                    List<List<String>> filtersBySignature = filtersByCommitTime.get(oldestCommit);
                    Instant finalOldestCommit = oldestCommit;
                    filtersBySignature.forEach(filters -> {
                        filters.forEach(filter -> {
                            FilterAPI filterAPI = apiMap.getOrDefault(filter, new FilterAPI(filter));
                            filterAPI.addOccurrenceTime(finalOldestCommit);
                            apiMap.put(filter, filterAPI);
                        });
                    });
                }

            } catch (GitAPIException e) {
                e.printStackTrace();
            }
        }

        if (trekkerMode.equals(Mode.SIGNATURECHANGE)) {
            System.out.println(String.format("All Signatures;%d\nUnmodified Signatures;%d\n", gitReltoCWDRelMap.size(), unmodified.size()));
            System.out.println(stringBuilder.toString());
        }

        if (trekkerMode.equals(Mode.FILTERAGE)) {
            apiMap.values().forEach(filterAPI -> {
                filterAPI.getOccurrencesTime().forEach(
                        instant -> stringBuilder.append(filterAPI.getName()).append(";")
                                // Fix toString Format so MS Excel can read it as a date
                                .append(instant.toString().replace('Z', ' ').replace('T', ' ')).append("\n"));
            });
            System.out.println(stringBuilder.toString());
        }

        git.close();

        // Cleanly close the repo again
        repo.close();
    }

    private void countFiltersInSignatures(HashMap<Path, List<String>> founds) {
        HashMap<String, Integer> filtersPerSignature = new HashMap<>();

        for (Map.Entry<Path, List<String>> pathListEntry : founds.entrySet()) {

            List<String> value = pathListEntry.getValue();
            int i;
            for (i = 0; i < value.size(); i++) {
                String filterDefinition = value.get(i);
                List<String> filters = extraktFiltersFromString(filterDefinition);
                filtersPerSignature.put(String.format("%s-%d", pathListEntry.getKey().toString(), i), filters.size());
            }
        }

        System.out.println("Signature Path;Filter Count");
        for (Map.Entry<String, Integer> entry : filtersPerSignature.entrySet()) {
            System.out.println(String.format("%s;%d", entry.getKey(), entry.getValue()));
        }

    }

    private void countCombinations(HashMap<Path, List<String>> founds, int n) {
        HashMap<String, FilterAPI> apiMap = new HashMap<>();
        for (List<String> list : founds.values()) {
            for (String found : list) {
                List<String> apiNames = extraktFiltersFromString(found);

                for (String apiName : apiNames) {
                    FilterAPI api = apiMap.getOrDefault(apiName, new FilterAPI(apiName));
                    for (String s : apiNames) {
                        if (!s.equals(apiName))
                            api.addCombinationWith(s);
                    }
                    api.count();
                    apiMap.put(apiName, api);
                }
            }

        }

        for (FilterAPI api : apiMap.values()) {
            if (api.getCount() >= n) {
                StringBuilder builder = new StringBuilder();
                builder.append(api.getName()).append(";;;Count;").append(api.getCount()).append("\n");
                for (Map.Entry<String, Integer> entry : api.getCombinations().entrySet()) {
                    double percentage = (double) entry.getValue() / (double) api.getCount();
                    builder.append(entry.getKey()).append(";").append(entry.getValue())
                            .append(";").append(NumberFormat.getInstance().format(percentage)).append("\n");
                }
                System.out.println(builder.toString());
            }
        }
    }

    public List<String> findRegex(Path path) {
        ArrayList<String> foundStrings = new ArrayList<>();
        Pattern pattern = Pattern.compile(regex);
        try {
            List<String> strings = Files.readAllLines(path);

            if (noLineBreaks) {
                String joined = String.join("", strings);
                Matcher matcher = pattern.matcher(joined);
                while (matcher.find()) {
//                    System.out.println(path.toString()+": "+matcher.group());
                    foundStrings.add(matcher.group());
                }
            } else {
                strings.forEach(s -> {
                    Matcher matcher = pattern.matcher(s);
                    while (matcher.find()) {
//                        System.out.println(path.toString()+": "+matcher.group());
                        foundStrings.add(matcher.group());
                    }
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return foundStrings;
    }

    public List<String> extraktFiltersFromString(String string) {
        // filter_apinames = [        "IWbemServices_ExecMethod",        "IWbemServices_ExecMethodAsync",
        int first = string.indexOf('"');
        if (first != -1)
            string = string.substring(string.indexOf('"'));
        string = string.replaceAll("\"", "");

        // IWbemServices_ExecMethod,        IWbemServices_ExecMethodAsync,
        String[] split = string.trim().split(",");
        for (int i = 0; i < split.length; i++) {
            split[i] = split[i].trim();
        }
        // IWbemServices_ExecMethod
        // IWbemServices_ExecMethodAsync

        // Remove Duplicates
        return Arrays.stream(split).distinct().collect(Collectors.toList());

    }

    public Stream<Path> getAllFiles(String directory) {

        try {
            return Files.walk(Paths.get(directory)).filter(Files::isRegularFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public enum Mode {
        COUNT, COMBINATIONS, FILTERAGE, SIGNATURECHANGE
    }
}
