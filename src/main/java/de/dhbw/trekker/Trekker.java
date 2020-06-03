package de.dhbw.trekker;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
        HashMap<Path, List<String>> founds = new HashMap<>();
        allFiles.forEach(path -> {
            founds.put(path, findRegex(path));
        });


        switch (trekkerMode) {
            case COUNT -> countFiltersInSignatures(founds);
            case COMBINATIONS -> countCombinations(founds, n);
            case SIGNATURECHANGE -> countSignatureChanges(directory, founds.keySet());
        }

    }

    private void countSignatureChanges(String directory, Set<Path> files) {

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

        ArrayList<Path> relativePaths = new ArrayList<>();
        for (Path path : files) {
            relativePaths.add(gitDirParent.relativize(path));
        }
        ArrayList<Path> unmodified = new ArrayList<>(relativePaths);

        StringBuilder stringBuilder = new StringBuilder("Signature;ID;Author;Message;FilesTouchedByCommit\n");
        for (Path signatur : relativePaths) {

            stringBuilder.append(signatur.toString()).append("\n");
            // change path seperator from "\" to "/" so JGit can work with it
            try {
                Iterable<RevCommit> commits = git.log().addPath(signatur.toString().replace('\\', '/')).call();
                int count = 0;
                RevWalk revWalk = new RevWalk(repo);
                for (RevCommit commit : commits) {
                    count++;
                    revWalk.reset();
                    try (ObjectReader reader = git.getRepository().newObjectReader()) {
                        commit = revWalk.parseCommit(commit.getId());

                        AbstractTreeIterator newTree = new CanonicalTreeParser(null, reader, commit.getTree());
                        AbstractTreeIterator oldTree;
                        if (commit.getParentCount() != 0) {
                            RevCommit parent = commit.getParent(0);
                            parent = revWalk.parseCommit(parent.getId());
                            oldTree = new CanonicalTreeParser(null, reader, parent.getTree());
                        } else {
                            oldTree = new EmptyTreeIterator();
                        }

//                        try (DiffFormatter formatter = new DiffFormatter(OutputStream.nullOutputStream())) {
//                            formatter.setRepository(repo);
//                            List<DiffEntry> diffEntries = formatter.scan(oldTree, newTree);
//                            for (DiffEntry diffEntry : diffEntries) {
//                                FileHeader fileHeader = formatter.toFileHeader(diffEntry);
//                                fileHeader.
//                            }
//                        }

                        ByteArrayOutputStream os = new ByteArrayOutputStream();

                        List<DiffEntry> diffEntries = git.diff().setOldTree(oldTree).setNewTree(newTree).setOutputStream(os).call();

                        // Check for multiple files in diff
                        Set<String> filePaths = new HashSet<>();

                        for (DiffEntry diffEntry : diffEntries) {
                            filePaths.add(diffEntry.getNewPath());
                        }

                        // Remove signature from unmodified list if multiple commits per signature and commit only touches this signature
                        if (count>1) {
                            if (filePaths.size()==1) {
                                unmodified.remove(signatur);
                            }
                        }



                        // Search for content in the diff
                        String diff = os.toString();

                        stringBuilder.append(";").append(commit.getId().toString()).append(";")
                                .append(commit.getAuthorIdent().getName()).append(";")
                                .append(commit.getShortMessage()).append(";")
                                .append(filePaths.size()).append(";")
                                .append(unmodified.contains(signatur)).append("\n");

                    } catch (IOException e) {
                        e.printStackTrace();
                    }


//                    Instant time = Instant.ofEpochSecond(commit.getCommitTime());
//                    stringBuilder.append(signatur.toString()).append(";")
//                            .append(commit.getId().toObjectId().toString()).append(";")
//                            .append(commit.getAuthorIdent().getName()).append(";")
//                            .append(commit.getShortMessage()).append(";")
//                            .append(time.toString().replace('Z', ' ').replace('T', ' ')).append(";")
//                            .append(count).append("\n");
                }
//                stringBuilder.append(signatur).append(";").append(count).append("\n");
            } catch (GitAPIException e) {
                e.printStackTrace();
            }
//            stringBuilder.append("\n");
        }

        System.out.println(String.format("All Signatures;%d\nUnmodified Signatures;%d\n", relativePaths.size(), unmodified.size()));
//        stringBuilder.append("\n\n")
//                .append("All Signatures;").append(relativePaths.size()).append("\n")
//                .append("Unmodified Signatures;").append(unmodified.size());
        System.out.println(stringBuilder.toString());

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
        List<String> collect = Arrays.stream(split).distinct().collect(Collectors.toList());
        return collect;

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
        COUNT, COMBINATIONS, FILTERAGE, SIGNATURECHANGE;
    }
}
