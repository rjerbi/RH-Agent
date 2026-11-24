package com.rh.agent.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rh.agent.service.RecruitmentKnowledgeLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final String CRITERIA_QUESTION = "Which skill or criteria should I search for?";
    private static final String EXPERIENCE_QUESTION =
            "Which candidate's experience would you like to check? Please provide the full name.";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final SyncMcpToolCallbackProvider tools;
    private final ObjectMapper objectMapper;
    private final QuestionAnswerAdvisor knowledgeAdvisor;
    private final RecruitmentKnowledgeLoader knowledgeLoader;
    private final Map<String, PendingInvitation> pendingInvitations = new ConcurrentHashMap<>();

    public ChatController(
            ChatClient.Builder chatClient,
            ChatMemory chatMemory,
            SyncMcpToolCallbackProvider tools,
            ObjectMapper objectMapper,
            VectorStore vectorStore,
            RecruitmentKnowledgeLoader knowledgeLoader) {

        this.chatMemory = chatMemory;
        this.tools = tools;
        this.objectMapper = objectMapper;
        this.knowledgeAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().topK(1).build())
                .build();
        this.knowledgeLoader = knowledgeLoader;
        this.chatClient = chatClient
                .defaultSystem("""
                    You are a friendly RH recruitment assistant.

                    Rules:
                    1. Use searchCandidates to find candidates by skill.
                    2. Use searchCandidateByName to find a candidate by name.
                    3. Use getAllCandidates when asked to show all candidates.
                    4. If no skill is given, ask:
                       "Which skill or criteria should I search for?"
                    5. Ignore the final word "skill" or "skills" when searching.
                       Example: "Spring Boot skill" means search for "Spring Boot".
                       Correct "machin learning" to "machine learning".
                    6. Use only database results for candidate names, emails, skills,
                       and experience. Never invent candidate information.
                    7. Use conversation history for follow-up questions.
                       If a name is unclear, ask the user which candidate they mean.
                    8. Answer briefly and naturally. Search the database for each new request.
                    9. Answer unrelated questions directly.
                    10. For recruitment performance, call analyzeRecruitmentData.
                        Pass candidate IDs from MySQL results, separated by commas.
                        CSV values are sample data, not verified candidate history.
                    11. Send an interview invitation only
                        when the user asks to send it.
                        Only invite candidates whose CSV status is "Interview Pending".
                        Ask for the candidate, date, and time if any are missing.
                        Use the email address from MySQL. Confirm sending only after
                        the sendInterviewInvitation tool succeeds.
                    12. For questions about recruitment status, use
                        getCandidatesByStatus. The CSV status is authoritative
                        for this sample recruitment workflow.
                    13. For questions about interview steps, score meanings, or
                        evaluation criteria, answer only from the retrieved HR
                        documents. If they do not contain the answer, say so.
                        Keep score
                        labels and ranges exactly as written, cite the source PDF,
                        and do not infer candidate suitability from a score.
                    14. For candidate experience, use searchCandidateByName and
                        report the experience from MySQL. If no candidate is named,
                        ask which candidate the user means.
                """)
                .defaultAdvisors(advisors -> advisors
                        .advisors(
                                MessageChatMemoryAdvisor.builder(chatMemory).build()
                        )
                )
                .defaultTools(tools)
                .build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message,
                       @RequestParam(defaultValue = "default-user") String conversationId) {

        String request = message.trim();
        String lowerRequest = request.toLowerCase();
        String requestedStatus = getRequestedStatus(lowerRequest);

        if (lowerRequest.contains("all candidate")) {
            String result = getCandidateToolResult("getAllCandidates", "{}");
            return rememberTurn(conversationId, message, formatCandidates(
                    result,
                    "All candidates in the database:",
                    "There are no candidates in the database."));
        }

        if (lowerRequest.equals("find candidates") || lowerRequest.equals("find candidate")) {
            return rememberTurn(conversationId, message, CRITERIA_QUESTION);
        }

        if ((lowerRequest.contains("send") && lowerRequest.contains("invitation"))
                || (lowerRequest.startsWith("invite ") && lowerRequest.contains("interview"))) {
            return sendInterviewInvitation(conversationId, message, request);
        }

        if (!requestedStatus.isBlank()
                && (lowerRequest.contains("status")
                    || lowerRequest.contains("candidate")
                    || lowerRequest.startsWith("who "))) {
            String input = objectMapper.writeValueAsString(Map.of("status", requestedStatus));
            JsonNode candidates = getCandidateRecords(
                    getCandidateToolResult("getCandidatesByStatus", input));
            return rememberTurn(conversationId, message,
                    formatCandidatesWithStatus(candidates, requestedStatus));
        }

        int withIndex = lowerRequest.indexOf(" with ");
        if (withIndex >= 0 && lowerRequest.contains("candidate")) {
            String skill = request.substring(withIndex + 6).trim();
            if (skill.toLowerCase().endsWith(" skills")) {
                skill = skill.substring(0, skill.length() - 7).trim();
            }
            return searchCandidates(conversationId, message, skill);
        }

        PendingInvitation pending = pendingInvitations.get(conversationId);
        if (pending != null) {
            return continueInvitation(conversationId, message, pending);
        }

        String lastAnswer = getLastAssistantMessage(conversationId);
        if (EXPERIENCE_QUESTION.equals(lastAnswer)) {
            return findCandidateExperience(conversationId, message, request);
        }

        if (isExperienceRequest(lowerRequest)) {
            String candidateName = getCandidateNameFromExperienceRequest(request);
            if (candidateName.isBlank()) {
                return rememberTurn(conversationId, message, EXPERIENCE_QUESTION);
            }
            return findCandidateExperience(conversationId, message, candidateName);
        }

        if (CRITERIA_QUESTION.equals(lastAnswer)) {
            return searchCandidates(conversationId, message, request);
        }

        if (lowerRequest.contains("performance")) {
            String candidateIds = getCandidateIds(lastAnswer);
            if (!candidateIds.isBlank()) {
                String input = objectMapper.writeValueAsString(
                        Map.of("candidateIds", candidateIds));
                String answer = getToolText("analyzeRecruitmentData", input);
                return rememberTurn(conversationId, message, answer);
            }
        }

        if (hasAmbiguousPronoun(lowerRequest) && countCandidates(lastAnswer) > 1) {
            return rememberTurn(conversationId, message, "Which candidate do you mean? Please tell me the name.");
        }

        Integer score = getRequestedScore(lowerRequest);
        if (score != null && isScoreInterpretationQuestion(lowerRequest)) {
            String scoreType = lowerRequest.contains("communication")
                    ? "communication screening"
                    : "technical screening";
            return rememberTurn(conversationId, message,
                    knowledgeLoader.interpretScore(score, scoreType));
        }

        if (isHrKnowledgeQuestion(lowerRequest)) {
            knowledgeLoader.loadIfNeeded();
            return chatClient.prompt()
                    .user(message)
                    .advisors(knowledgeAdvisor)
                    .advisors(advisor -> advisor.param(
                            ChatMemory.CONVERSATION_ID,
                            conversationId
                    ))
                    .call()
                    .content();
        }

        return chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(
                        ChatMemory.CONVERSATION_ID,
                        conversationId
                ))
                .call()
                .content();
    }

    private boolean isExperienceRequest(String request) {
        return request.equals("check experience")
                || (request.startsWith("what is ") && request.contains("experience"))
                || request.startsWith("check experience for ")
                || request.startsWith("experience for ");
    }

    private String getCandidateNameFromExperienceRequest(String request) {
        String lowerRequest = request.toLowerCase().trim();
        String prefix = "what is ";
        String possessiveMarker = "'s experience";
        if (lowerRequest.startsWith(prefix)) {
            int end = lowerRequest.indexOf(possessiveMarker);
            if (end > prefix.length()) {
                return request.substring(prefix.length(), end).trim();
            }
        }

        prefix = "check experience for ";
        if (lowerRequest.startsWith(prefix)) {
            return removeTrailingPunctuation(request.substring(prefix.length()));
        }

        prefix = "experience for ";
        if (lowerRequest.startsWith(prefix)) {
            return removeTrailingPunctuation(request.substring(prefix.length()));
        }

        return "";
    }

    private String findCandidateExperience(
            String conversationId,
            String userMessage,
            String candidateName) {

        String input = objectMapper.writeValueAsString(Map.of("name", candidateName.trim()));
        JsonNode candidates = getCandidateRecords(
                getCandidateToolResult("searchCandidateByName", input));

        if (!candidates.isArray()) {
            throw new IllegalStateException("MCP candidate tool returned an unexpected response.");
        }
        if (candidates.isEmpty()) {
            return rememberTurn(conversationId, userMessage,
                    "I couldn't find that candidate in MySQL. Please enter the correct full name.");
        }
        if (candidates.size() > 1) {
            return rememberTurn(conversationId, userMessage,
                    "More than one candidate matches. Please enter the full name.");
        }

        JsonNode candidate = candidates.get(0);
        String name = candidate.path("name").asString();
        String experience = candidate.path("experience").asString();
        if (experience == null || experience.isBlank()) {
            return rememberTurn(conversationId, userMessage,
                    "No experience is listed for " + name + " in MySQL.");
        }

        return rememberTurn(conversationId, userMessage,
                name + " has " + experience + " of experience.");
    }

    private String removeTrailingPunctuation(String value) {
        String result = value.trim();
        while (result.endsWith(".") || result.endsWith("!") || result.endsWith("?")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private boolean isHrKnowledgeQuestion(String request) {
        boolean asksAboutInterviewProcess = request.contains("interview")
                && (request.contains("process")
                    || request.contains("step")
                    || request.contains("stage")
                    || request.contains("round"));
        boolean asksAboutScoreMeaning = request.contains("score")
                && (request.contains("mean")
                    || request.contains("interpret")
                    || request.contains("scale")
                    || request.contains("explain"));
        boolean asksAboutEvaluation = request.contains("criteria")
                && (request.contains("evaluat")
                    || request.contains("assess")
                    || request.contains("screen"));
        return asksAboutInterviewProcess
                || asksAboutScoreMeaning
                || asksAboutEvaluation
                || request.contains("how to evaluate candidates");
    }

    private boolean isScoreInterpretationQuestion(String request) {
        return request.contains("score")
                && (request.contains("mean")
                    || request.contains("interpret")
                    || request.contains("scale")
                    || request.contains("explain"));
    }

    private Integer getRequestedScore(String request) {
        Matcher score = Pattern.compile("\\b(\\d{1,3})\\b").matcher(request);
        return score.find() ? Integer.valueOf(score.group(1)) : null;
    }

    private String getRequestedStatus(String request) {
        String normalized = request.replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim();
        if (normalized.contains("interview pending")) {
            return "Interview Pending";
        }
        if (normalized.contains("hired")) {
            return "Hired";
        }
        if (normalized.contains("rejected")) {
            return "Rejected";
        }
        return "";
    }

    private String formatCandidatesWithStatus(JsonNode candidates, String status) {
        if (!candidates.isArray()) {
            throw new IllegalStateException("MCP candidate tool returned an unexpected response.");
        }
        if (candidates.isEmpty()) {
            return "No candidates currently have the status \"" + status + "\".";
        }

        StringBuilder answer = new StringBuilder("Candidates with status \"")
                .append(status).append("\":");
        int number = 1;
        for (JsonNode candidate : candidates) {
            String name = candidate.path("name").asString();
            if (name == null || name.isBlank()) {
                continue;
            }
            answer.append("\n\n").append(number++).append(". ").append(name);
        }
        return number == 1
                ? "No candidates currently have the status \"" + status + "\"."
                : answer.toString();
    }

    private String sendInterviewInvitation(String conversationId, String message, String request) {
        String lowerRequest = request.toLowerCase();
        int toIndex = lowerRequest.indexOf(" to ");
        int onIndex = lowerRequest.indexOf(" on ");
        int atIndex = lowerRequest.lastIndexOf(" at ");

        String candidateName = "";
        String date = "";
        String time = "";

        if (toIndex >= 0) {
            int end = onIndex > toIndex ? onIndex : request.length();
            candidateName = request.substring(toIndex + 4, end).trim();
        }
        if (onIndex >= 0) {
            int end = atIndex > onIndex ? atIndex : request.length();
            date = request.substring(onIndex + 4, end).trim();
        }
        if (atIndex >= 0) {
            time = request.substring(atIndex + 4).trim();
        }

        if (candidateName.isBlank() || date.isBlank() || time.isBlank()) {
            return rememberTurn(conversationId, message,
                    "Please include the candidate name, date, and time. Example: "
                            + "Send an interview invitation to Sara Trabelsi on 2026-11-20 at 10:00.");
        }

        PendingInvitation pending = new PendingInvitation(date, time);
        pendingInvitations.put(conversationId, pending);
        return findInvitationCandidate(conversationId, message, candidateName, pending);
    }

    private String continueInvitation(
            String conversationId,
            String message,
            PendingInvitation pending) {

        String reply = message.trim().toLowerCase();
        if (reply.equals("yes") || reply.startsWith("yes ")) {
            if (pending.candidateName.isBlank()) {
                return rememberTurn(conversationId, message,
                        "Please provide the correct candidate name before confirming.");
            }

            String input = objectMapper.writeValueAsString(Map.of(
                    "candidateName", pending.candidateName,
                    "date", pending.date,
                    "time", pending.time));
            String result = getToolText("sendInterviewInvitation", input);
            pendingInvitations.remove(conversationId);
            return rememberTurn(conversationId, message, result);
        }

        if (reply.equals("no") || reply.equals("cancel")) {
            pendingInvitations.remove(conversationId);
            return rememberTurn(conversationId, message, "The invitation was cancelled.");
        }

        String candidateName = getCorrectedCandidateName(message);
        return findInvitationCandidate(conversationId, message, candidateName, pending);
    }

    private String findInvitationCandidate(
            String conversationId,
            String message,
            String candidateName,
            PendingInvitation pending) {

        String input = objectMapper.writeValueAsString(Map.of("name", candidateName));
        String result = getCandidateToolResult("searchCandidateByName", input);
        JsonNode candidates = getCandidateRecords(result);

        if (!candidates.isArray() || candidates.isEmpty()) {
            pending.candidateName = "";
            return rememberTurn(conversationId, message,
                    "I couldn't find that candidate in MySQL. Please enter the correct full name.");
        }
        if (candidates.size() > 1) {
            pending.candidateName = "";
            return rememberTurn(conversationId, message,
                    "More than one candidate matches. Please enter the full name.");
        }

        pending.candidateName = candidates.get(0).path("name").asString();
        return rememberTurn(conversationId, message,
                "I found " + pending.candidateName + ". Send the invitation for "
                        + pending.date + " at " + addAmIfMissing(pending.time)
                        + "? Reply yes or no.");
    }

    private String getCorrectedCandidateName(String message) {
        String lowerMessage = message.toLowerCase();
        int meantIndex = lowerMessage.lastIndexOf("i meant ");
        String name = meantIndex >= 0
                ? message.substring(meantIndex + 8).trim()
                : message.trim();

        while (name.endsWith(".") || name.endsWith("!") || name.endsWith("?")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        return name;
    }

    private String addAmIfMissing(String time) {
        String upperTime = time.toUpperCase();
        if (upperTime.endsWith("AM") || upperTime.endsWith("PM")) {
            return time;
        }
        return time + " AM";
    }

    private String searchCandidates(String conversationId, String message, String skill) {
        skill = cleanSkill(skill);
        if (skill.isBlank()) {
            return rememberTurn(conversationId, message, CRITERIA_QUESTION);
        }

        List<String> skills = splitSkills(skill);
        if (skills.isEmpty()) {
            return rememberTurn(conversationId, message, CRITERIA_QUESTION);
        }

        JsonNode candidates = searchBySkill(skills.get(0));
        for (int i = 1; i < skills.size(); i++) {
            JsonNode nextResults = searchBySkill(skills.get(i));
            candidates = keepCandidatesFoundInBoth(candidates, nextResults);
        }

        return rememberTurn(conversationId, message, formatCandidateRecords(
                candidates,
                "Candidates matching \"" + skill + "\":",
                "No candidates in the database match \"" + skill + "\"."));
    }

    private List<String> splitSkills(String skill) {
        List<String> skills = new ArrayList<>();

        while (skill.toLowerCase().contains(" and ")) {
            int andIndex = skill.toLowerCase().indexOf(" and ");
            String nextSkill = skill.substring(0, andIndex).trim();
            if (!nextSkill.isBlank()) {
                skills.add(nextSkill);
            }
            skill = skill.substring(andIndex + 5).trim();
        }

        if (!skill.isBlank()) {
            skills.add(skill);
        }
        return skills;
    }

    private JsonNode keepCandidatesFoundInBoth(JsonNode firstList, JsonNode secondList) {
        ArrayNode matches = objectMapper.createArrayNode();

        for (JsonNode firstCandidate : firstList) {
            for (JsonNode secondCandidate : secondList) {
                if (firstCandidate.path("id").equals(secondCandidate.path("id"))) {
                    matches.add(firstCandidate);
                    break;
                }
            }
        }
        return matches;
    }

    private JsonNode searchBySkill(String skill) {
        String input = objectMapper.writeValueAsString(Map.of("skills", skill));
        String result = getCandidateToolResult("searchCandidates", input);
        return getCandidateRecords(result);
    }

    private String cleanSkill(String skill) {
        skill = skill.trim();
        while (skill.endsWith(".") || skill.endsWith("?") || skill.endsWith("!")) {
            skill = skill.substring(0, skill.length() - 1).trim();
        }

        String lowerSkill = skill.toLowerCase();
        if (lowerSkill.endsWith(" skills")) {
            skill = skill.substring(0, skill.length() - 7);
        } else if (lowerSkill.endsWith(" skill")) {
            skill = skill.substring(0, skill.length() - 6);
        }

        skill = skill.trim();
        int typoIndex = skill.toLowerCase().indexOf("machin learning");
        if (typoIndex >= 0) {
            skill = skill.substring(0, typoIndex)
                    + "machine learning"
                    + skill.substring(typoIndex + "machin learning".length());
        }
        return skill;
    }

    private String getCandidateToolResult(String toolName, String input) {
        for (ToolCallback callback : tools.getToolCallbacks()) {
            if (callback instanceof SyncMcpToolCallback) {
                SyncMcpToolCallback mcpTool = (SyncMcpToolCallback) callback;
                if (toolName.equals(mcpTool.getOriginalToolName())) {
                    return mcpTool.call(input);
                }
            }
        }
        throw new IllegalStateException("MCP candidate tool not found: " + toolName);
    }

    private String formatCandidates(String toolResult, String heading, String emptyMessage) {
        return formatCandidateRecords(getCandidateRecords(toolResult), heading, emptyMessage);
    }

    private JsonNode getCandidateRecords(String toolResult) {
        JsonNode records = objectMapper.readTree(toolResult);
        if (records.isArray() && records.size() > 0 && records.get(0).has("text")) {
            records = objectMapper.readTree(records.get(0).path("text").asString());
        }
        return records;
    }

    private String formatCandidateRecords(JsonNode records, String heading, String emptyMessage) {
        if (!records.isArray()) {
            throw new IllegalStateException("MCP candidate tool returned an unexpected response.");
        }
        if (records.isEmpty()) {
            return emptyMessage;
        }

        StringBuilder answer = new StringBuilder(heading);
        int number = 1;
        for (JsonNode candidate : records) {
            String name = candidate.path("name").asString();
            if (name == null || name.isBlank()) {
                continue;
            }

            answer.append("\n\n").append(number++).append(". ").append(name);
            if (candidate.hasNonNull("id")) {
                answer.append("\n   ID: ").append(candidate.path("id").asString());
            }
            if (candidate.hasNonNull("email")) {
                answer.append("\n   Email: ").append(candidate.path("email").asString());
            }
            if (candidate.hasNonNull("skills")) {
                answer.append("\n   Skills: ").append(candidate.path("skills").asString());
            }
            if (candidate.hasNonNull("experience")) {
                answer.append("\n   Experience: ").append(candidate.path("experience").asString());
            }
        }
        return number == 1 ? emptyMessage : answer.toString();
    }

    private String getLastAssistantMessage(String conversationId) {
        List<Message> history = chatMemory.get(conversationId);
        String lastAnswer = "";
        for (Message message : history) {
            if (message instanceof AssistantMessage) {
                AssistantMessage assistantMessage = (AssistantMessage) message;
                lastAnswer = assistantMessage.getText();
            }
        }
        return lastAnswer;
    }

    private String getCandidateIds(String answer) {
        List<String> ids = new ArrayList<>();
        for (String line : answer.split("\\R")) {
            String trimmedLine = line.trim();
            if (trimmedLine.startsWith("ID: ")) {
                ids.add(trimmedLine.substring(4));
            }
        }
        return String.join(",", ids);
    }

    private String getToolText(String toolName, String input) {
        String result = getCandidateToolResult(toolName, input);
        JsonNode response = objectMapper.readTree(result);
        if (response.isArray() && response.size() > 0 && response.get(0).has("text")) {
            return response.get(0).path("text").asString();
        }
        throw new IllegalStateException("MCP tool returned an unexpected response: " + toolName);
    }

    private boolean hasAmbiguousPronoun(String message) {
        for (String word : message.split("\\W+")) {
            if (word.equals("her") || word.equals("his") || word.equals("she") || word.equals("he")) {
                return true;
            }
        }
        return false;
    }

    private int countCandidates(String message) {
        int count = 0;
        for (String line : message.split("\\R")) {
            if (line.indexOf(". ") == 1) {
                count++;
            }
        }
        return count;
    }

    private String rememberTurn(String conversationId, String userMessage, String assistantMessage) {
        chatMemory.add(conversationId, List.of(
                new UserMessage(userMessage),
                new AssistantMessage(assistantMessage)));
        return assistantMessage;
    }

    @DeleteMapping("/chat/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        chatMemory.clear(conversationId);
        pendingInvitations.remove(conversationId);
        return ResponseEntity.noContent().build();
    }

    private static class PendingInvitation {
        private String candidateName = "";
        private final String date;
        private final String time;

        private PendingInvitation(String date, String time) {
            this.date = date;
            this.time = time;
        }
    }
}