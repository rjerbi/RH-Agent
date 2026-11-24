package com.rh.mcp.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import com.rh.mcp.entities.Candidate;
import com.rh.mcp.repositories.CandidateRepository;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CandidateTools {

    private final CandidateRepository candidateRepository;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.from}")
    private String fromEmail;

    public CandidateTools(CandidateRepository candidateRepository, JavaMailSender mailSender) {
        this.candidateRepository = candidateRepository;
        this.mailSender = mailSender;
    }

    @McpTool(description = "Search candidates by their technical skills.")
    public List<Candidate> searchCandidates(String skills) {
        return candidateRepository
                .findBySkillsContainingIgnoreCase(skills);
    }

    @McpTool(description = "Retrieve all candidates from the recruitment database.")
    public List<Candidate> getAllCandidates() {
        return candidateRepository.findAll();
    }

    @McpTool(description = "Find candidates by recruitment status from recruitment_data.csv, matching CSV candidate IDs with MySQL.")
    public List<Candidate> getCandidatesByStatus(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }

        Set<Long> candidateIds = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new ClassPathResource("recruitment_data.csv").getInputStream(),
                        StandardCharsets.UTF_8))) {

            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",", -1);
                if (values.length != 6) {
                    throw new IllegalStateException("Invalid row in recruitment_data.csv: " + line);
                }
                if (values[5].trim().equalsIgnoreCase(status.trim())) {
                    candidateIds.add(Long.parseLong(values[0].trim()));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read recruitment_data.csv.", exception);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid candidate ID in recruitment_data.csv.", exception);
        }

        return candidateRepository.findAll().stream()
                .filter(candidate -> candidateIds.contains(candidate.getId()))
                .toList();
    }

    @McpTool(description = "Find a candidate by their name.")
    public List<Candidate> searchCandidateByName(String name) {
        return candidateRepository
                .findByNameContainingIgnoreCase(name);
    }

    @McpTool(description = "Analyze the CSV recruitment data for candidate IDs returned by MySQL. Pass IDs separated by commas.")
    public String analyzeRecruitmentData(String candidateIds) {
        if (candidateIds == null || candidateIds.isBlank()) {
            return "Please provide candidate IDs from the database.";
        }

        int candidateCount = 0;
        int totalApplications = 0;
        int totalInterviews = 0;
        int totalTechnicalScore = 0;
        int totalCommunicationScore = 0;
        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new ClassPathResource("recruitment_data.csv").getInputStream(),
                        StandardCharsets.UTF_8))) {

            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");
                if (values.length != 6) {
                    throw new IllegalStateException("Invalid row in recruitment_data.csv: " + line);
                }
                if (!containsCandidateId(candidateIds, values[0])) {
                    continue;
                }

                int applications = Integer.parseInt(values[1]);
                int interviews = Integer.parseInt(values[2]);
                int technicalScore = Integer.parseInt(values[3]);
                int communicationScore = Integer.parseInt(values[4]);

                result.append("Candidate ID ").append(values[0])
                        .append(": applications=").append(applications)
                        .append(", interviews=").append(interviews)
                        .append(", screening technical score=").append(technicalScore)
                        .append(", screening communication score=").append(communicationScore)
                        .append(", status=").append(values[5]).append("\n");

                candidateCount++;
                totalApplications += applications;
                totalInterviews += interviews;
                totalTechnicalScore += technicalScore;
                totalCommunicationScore += communicationScore;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read recruitment_data.csv.", exception);
        }

        if (candidateCount == 0) {
            return "No recruitment data found for those candidate IDs.";
        }

        result.append("Summary for ").append(candidateCount).append(" candidate(s):")
                .append("\nTotal applications: ").append(totalApplications)
                .append("\nTotal interviews: ").append(totalInterviews)
                .append("\nAverage screening technical score: ")
                .append((double) totalTechnicalScore / candidateCount)
                .append("\nAverage screening communication score: ")
                .append((double) totalCommunicationScore / candidateCount);

        return result.toString();
    }

    private boolean containsCandidateId(String candidateIds, String id) {
        for (String candidateId : candidateIds.split(",")) {
            if (candidateId.trim().equals(id)) {
                return true;
            }
        }
        return false;
    }

    @McpTool(description = "Send an interview invitation only when the user explicitly requests it. Use a candidate name from MySQL and provide the interview date and time.")
    public String sendInterviewInvitation(String candidateName, String date, String time) {
        if (candidateName == null || candidateName.isBlank()
                || date == null || date.isBlank()
                || time == null || time.isBlank()) {
            return "Please provide the candidate name, interview date, and interview time.";
        }

        List<Candidate> candidates =
                candidateRepository.findByNameContainingIgnoreCase(candidateName);
        if (candidates.isEmpty()) {
            return "No candidate with that name was found in the database.";
        }
        if (candidates.size() > 1) {
            return "More than one candidate matches. Please provide the full name.";
        }

        Candidate candidate = candidates.get(0);
        if (candidate.getEmail() == null || candidate.getEmail().isBlank()) {
            return "This candidate has no email address in the database.";
        }

        String status = getCandidateStatus(candidate.getId());
        if (status.isBlank()) {
            return "No recruitment status was found for this candidate. No email was sent.";
        }
        if (!status.equalsIgnoreCase("Interview Pending")) {
            return "No email was sent. The candidate's status is " + status
                    + ", not Interview Pending.";
        }

        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom("DeepIT RH Recruitment <" + fromEmail + ">");
        email.setTo(candidate.getEmail());
        email.setSubject("Interview invitation - DeepIT");
        email.setText("Hello " + candidate.getName() + ",\n\n"
                + "Thank you for your interest in DeepIT. We are pleased to invite you "
                + "to an interview on " + date + " at " + addAmIfMissing(time) + ".\n\n"
                + "We look forward to meeting you.\n\n"
                + "Best regards,\nDeepIT RH Recruitment");

        mailSender.send(email);
        return "Interview invitation sent to " + candidate.getName()
                + " at " + candidate.getEmail() + ".";
    }

    private String getCandidateStatus(Long candidateId) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new ClassPathResource("recruitment_data.csv").getInputStream(),
                        StandardCharsets.UTF_8))) {

            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");
                if (values.length != 6) {
                    throw new IllegalStateException("Invalid row in recruitment_data.csv: " + line);
                }
                if (values[0].equals(candidateId.toString())) {
                    return values[5];
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read recruitment_data.csv.", exception);
        }
        return "";
    }

    private String addAmIfMissing(String time) {
        String upperTime = time.toUpperCase();
        if (upperTime.endsWith("AM") || upperTime.endsWith("PM")) {
            return time;
        }
        return time + " AM";
    }
}