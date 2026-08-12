package com.edunext.edutrack.api.feature.fixtures;

import com.edunext.edutrack.domain.clients.Client;
import com.edunext.edutrack.domain.clients.ClientContact;
import com.edunext.edutrack.domain.clients.ClientContactRepository;
import com.edunext.edutrack.domain.clients.ClientProject;
import com.edunext.edutrack.domain.clients.ClientProjectId;
import com.edunext.edutrack.domain.clients.ClientProjectRepository;
import com.edunext.edutrack.domain.clients.ClientRepository;
import com.edunext.edutrack.domain.identity.Project;
import com.edunext.edutrack.domain.identity.ProjectMember;
import com.edunext.edutrack.domain.identity.ProjectMemberRepository;
import com.edunext.edutrack.domain.identity.ProjectRepository;
import com.edunext.edutrack.domain.identity.Role;
import com.edunext.edutrack.domain.identity.RoleRepository;
import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.masters.SlaPolicy;
import com.edunext.edutrack.domain.masters.SlaPolicyRepository;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import com.edunext.edutrack.domain.workflow.WorkflowTemplate;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B-007 · the reference data the 200-ticket corpus is built on.
 *
 * <p>None of this existed anywhere before B-007: no demo users, projects or
 * clients are seeded by any B-00x migration (those seed only true reference
 * data — roles, task types, statuses, workflow templates — see
 * {@code SEED-MANIFEST.md}). {@link TicketFixtureGenerator} needs real rows to
 * point {@code tickets.reported_by}, {@code assigned_to}, {@code project_id}
 * and {@code client_id} at, so this class creates a small but coherent
 * organisation first: 3 projects (reusing the {@code CRM}/{@code PAY}/
 * {@code WEB} codes the frontend mock already uses — D-004's {@code
 * mocks/db.ts} — so both fixture worlds read as the same company), 18
 * resources across the 6 roles, 8 clients with contacts, and 4 org-wide
 * {@code sla_policies} rows the breach injector reads.
 *
 * <p>{@code sla_policies} is genuinely empty otherwise — B-018 (the
 * project-level SLA tab) has not been built. The 4 rows here are org-wide
 * defaults only (b {@code project_id IS NULL}, {@code task_type_id IS NULL}),
 * one per priority level, so {@link TicketFixtureGenerator} has something to
 * compare elapsed time against. A real project override, once B-018 exists,
 * simply outranks these on the resolution ladder — nothing here needs to
 * change.
 */
@Component
@Profile("fixtures")
class ReferenceDataFixture {

    /** One row per fixture user: emp suffix, username stem, full name, role code, department, designation. */
    private record UserSpec(String empSuffix, String username, String fullName, String roleCode,
                             String department, String designation) {
    }

    private static final List<UserSpec> USERS = List.of(
            new UserSpec("001", "priya.nair", "Priya Nair", "ADMIN", "Operations", "Head of Delivery"),
            new UserSpec("002", "karthik.subramaniam", "Karthik Subramaniam", "PM", "CRM Program", "Project Manager"),
            new UserSpec("003", "ananya.rao", "Ananya Rao", "PM", "Payments Program", "Project Manager"),
            new UserSpec("004", "devika.menon", "Devika Menon", "PM", "Web Program", "Project Manager"),
            new UserSpec("005", "farhan.sheikh", "Farhan Sheikh", "SUPPORT", "Client Services", "Support Desk Lead"),
            new UserSpec("006", "ritu.chawla", "Ritu Chawla", "SUPPORT", "Client Services", "Support Engineer"),
            new UserSpec("007", "ibrahim.qureshi", "Ibrahim Qureshi", "SUPPORT", "Client Services", "Support Engineer"),
            new UserSpec("008", "nikhil.bansal", "Nikhil Bansal", "DEVELOPER", "Engineering", "Senior Developer"),
            new UserSpec("009", "sneha.kulkarni", "Sneha Kulkarni", "DEVELOPER", "Engineering", "Developer"),
            new UserSpec("010", "arjun.malhotra", "Arjun Malhotra", "DEVELOPER", "Engineering", "Developer"),
            new UserSpec("011", "priyanka.desai", "Priyanka Desai", "DEVELOPER", "Engineering", "Senior Developer"),
            new UserSpec("012", "rohan.kapoor", "Rohan Kapoor", "DEVELOPER", "Engineering", "Developer"),
            new UserSpec("013", "simran.bhatia", "Simran Bhatia", "DEVELOPER", "Engineering", "Developer"),
            new UserSpec("014", "varun.chandra", "Varun Chandra", "QA", "Quality", "QA Engineer"),
            new UserSpec("015", "lakshmi.pillai", "Lakshmi Pillai", "QA", "Quality", "QA Engineer"),
            new UserSpec("016", "aditya.rawat", "Aditya Rawat", "QA", "Quality", "QA Lead"),
            new UserSpec("017", "tanvi.joshi", "Tanvi Joshi", "DEPLOYMENT", "DevOps", "Release Engineer"),
            new UserSpec("018", "manish.trivedi", "Manish Trivedi", "DEPLOYMENT", "DevOps", "Release Engineer"));

    private record ProjectSpec(String code, String name, String colourTag, String pmUsername,
                                List<String> memberUsernames) {
    }

    private static final List<ProjectSpec> PROJECTS = List.of(
            new ProjectSpec("CRM", "Client CRM Platform", "#4F46E5", "karthik.subramaniam",
                    List.of("farhan.sheikh", "nikhil.bansal", "sneha.kulkarni", "varun.chandra", "tanvi.joshi")),
            new ProjectSpec("PAY", "Payments Gateway", "#F59E0B", "ananya.rao",
                    List.of("ritu.chawla", "arjun.malhotra", "priyanka.desai", "lakshmi.pillai", "manish.trivedi")),
            new ProjectSpec("WEB", "Marketing Website", "#06B6D4", "devika.menon",
                    List.of("ibrahim.qureshi", "rohan.kapoor", "simran.bhatia", "aditya.rawat", "tanvi.joshi")));

    private record ContactSpec(String name, String designation, boolean primary) {
    }

    private record ClientSpec(String code, String name, String industry, String supportPlan,
                               List<String> projectCodes, List<ContactSpec> contacts) {
    }

    private static final List<ClientSpec> CLIENTS = List.of(
            new ClientSpec("MRD", "Meridian Retail Group", "Retail", "PREMIUM", List.of("CRM"),
                    List.of(new ContactSpec("Owen Castillo", "IT Director", true),
                            new ContactSpec("Grace Lindqvist", "Store Ops Manager", false))),
            new ClientSpec("SOL", "Solstice Health Systems", "Healthcare", "PREMIUM", List.of("CRM"),
                    List.of(new ContactSpec("Naomi Fischer", "VP Engineering", true))),
            new ClientSpec("ANC", "Anchorpoint Logistics", "Logistics", "STANDARD", List.of("PAY"),
                    List.of(new ContactSpec("Elliot Marsh", "Operations Lead", true),
                            new ContactSpec("Priya Chandran", "Finance Manager", false))),
            new ClientSpec("EVL", "Everline Media", "Media", "STANDARD", List.of("PAY", "WEB"),
                    List.of(new ContactSpec("Dana Okafor", "Head of Digital", true))),
            new ClientSpec("BRI", "Brightfield Insurance", "Insurance", "PREMIUM", List.of("WEB"),
                    List.of(new ContactSpec("Marcus Webb", "IT Manager", true),
                            new ContactSpec("Yvonne Park", "Marketing Director", false))),
            new ClientSpec("KES", "Kestrel Analytics", "Technology", "BASIC", List.of("WEB"),
                    List.of(new ContactSpec("Ravi Deshmukh", "CTO", true))),
            new ClientSpec("UFC", "Union Foods Cooperative", "Food & Beverage", "STANDARD", List.of("CRM", "PAY"),
                    List.of(new ContactSpec("Helena Brandt", "Supply Chain Manager", true))),
            new ClientSpec("HOL", "Hollowreef Studios", "Entertainment", "BASIC", List.of("PAY"),
                    List.of(new ContactSpec("Theo Marchetti", "Producer", true))));

    /** LOW/MEDIUM/HIGH/CRITICAL resolution hours, matching {@code priorities.default_sla_hours} (B-002). */
    private static final Map<String, Integer> RESOLUTION_HOURS_BY_LEVEL = new LinkedHashMap<>();

    static {
        RESOLUTION_HOURS_BY_LEVEL.put("LOW", 72);
        RESOLUTION_HOURS_BY_LEVEL.put("MEDIUM", 24);
        RESOLUTION_HOURS_BY_LEVEL.put("HIGH", 8);
        RESOLUTION_HOURS_BY_LEVEL.put("CRITICAL", 4);
    }

    /** Task type code to the B-004 template it walks, per that seed's own template descriptions. */
    private static final Map<String, String> TEMPLATE_BY_TASK_TYPE = new LinkedHashMap<>();

    static {
        for (String supportFastTrack : List.of("CLIENT_REQUEST", "BROWSER_ISSUE")) {
            TEMPLATE_BY_TASK_TYPE.put(supportFastTrack, "Support Fast-Track");
        }
        for (String infraFlow : List.of("SERVER_ISSUE", "NETWORK_ISSUE")) {
            TEMPLATE_BY_TASK_TYPE.put(infraFlow, "Infra Flow");
        }
        for (String standardDevFlow : List.of("CHANGE_REQUEST", "PRODUCTION_BUG", "FUTURE_RELEASE", "INTERNAL_BUG",
                "CLIENT_BUG", "PERFORMANCE_ISSUE", "OTHER")) {
            TEMPLATE_BY_TASK_TYPE.put(standardDevFlow, "Standard Dev Flow");
        }
    }

    /** A password no fixture user needs to actually log in with today — dev-noauth stands in until A-020 lands. */
    private static final String FIXTURE_PASSWORD = "Fixture#B007-2026";

    private final ProjectRepository projects;
    private final RoleRepository roles;
    private final UserRepository users;
    private final ProjectMemberRepository projectMembers;
    private final ClientRepository clients;
    private final ClientContactRepository clientContacts;
    private final ClientProjectRepository clientProjects;
    private final SlaPolicyRepository slaPolicies;
    private final WorkflowTemplateRepository workflowTemplates;
    private final WorkflowStageRepository workflowStages;
    private final PasswordEncoder passwordEncoder;

    ReferenceDataFixture(ProjectRepository projects, RoleRepository roles, UserRepository users,
                         ProjectMemberRepository projectMembers, ClientRepository clients,
                         ClientContactRepository clientContacts, ClientProjectRepository clientProjects,
                         SlaPolicyRepository slaPolicies, WorkflowTemplateRepository workflowTemplates,
                         WorkflowStageRepository workflowStages, PasswordEncoder passwordEncoder) {
        this.projects = projects;
        this.roles = roles;
        this.users = users;
        this.projectMembers = projectMembers;
        this.clients = clients;
        this.clientContacts = clientContacts;
        this.clientProjects = clientProjects;
        this.slaPolicies = slaPolicies;
        this.workflowTemplates = workflowTemplates;
        this.workflowStages = workflowStages;
        this.passwordEncoder = passwordEncoder;
    }

    /** {@code true} once CRM/PAY/WEB exist — the idempotency check {@link FixtureLoader} runs first. */
    @Transactional(readOnly = true)
    boolean alreadyLoaded() {
        return projects.existsByProjectCode(PROJECTS.get(0).code());
    }

    @Transactional
    FixtureContext load() {
        Map<String, Role> roleByCode = loadRoles();
        Map<String, User> userByUsername = createUsers(roleByCode);
        assignManagers(userByUsername);

        Map<String, Project> projectByCode = createProjects(userByUsername);
        Map<Long, List<Long>> projectMemberIds = createProjectMembers(projectByCode, userByUsername);

        Map<String, Client> clientByCode = createClients(userByUsername);
        Map<Long, Long> primaryContactByClient = createClientContacts(clientByCode);
        Map<Long, List<Long>> clientsByProject = mapClientsToProjects(clientByCode, projectByCode);

        createSlaPolicies();

        Map<Long, List<WorkflowStage>> stagesByTemplate = new LinkedHashMap<>();
        Map<String, Long> templateIdByTaskType = new LinkedHashMap<>();
        for (WorkflowTemplate template : workflowTemplates.findAll()) {
            stagesByTemplate.put(template.getId(), workflowStages.findByTemplateIdOrderBySeqAsc(template.getId()));
        }
        for (Map.Entry<String, String> entry : TEMPLATE_BY_TASK_TYPE.entrySet()) {
            WorkflowTemplate template = workflowTemplates.findByName(entry.getValue())
                    .orElseThrow(() -> new IllegalStateException(
                            "workflow template '" + entry.getValue() + "' is missing — B-004's seed did not run"));
            templateIdByTaskType.put(entry.getKey(), template.getId());
        }

        Map<String, List<Long>> usersByRole = new LinkedHashMap<>();
        for (UserSpec spec : USERS) {
            usersByRole.computeIfAbsent(spec.roleCode(), k -> new ArrayList<>())
                    .add(userByUsername.get(spec.username()).getId());
        }

        Map<Long, Long> projectManager = new LinkedHashMap<>();
        for (ProjectSpec spec : PROJECTS) {
            Project project = projectByCode.get(spec.code());
            projectManager.put(project.getId(), userByUsername.get(spec.pmUsername()).getId());
        }

        List<FixtureContext.ProjectRef> projectRefs = PROJECTS.stream()
                .map(spec -> {
                    Project project = projectByCode.get(spec.code());
                    return new FixtureContext.ProjectRef(project.getId(), project.getProjectCode(), project.getName());
                })
                .toList();

        Map<String, BigDecimal> resolutionHours = new LinkedHashMap<>();
        RESOLUTION_HOURS_BY_LEVEL.forEach((level, hrs) -> resolutionHours.put(level, BigDecimal.valueOf(hrs)));

        return new FixtureContext(projectRefs, usersByRole, projectMemberIds, projectManager, clientsByProject,
                primaryContactByClient, stagesByTemplate, templateIdByTaskType, resolutionHours);
    }

    private Map<String, Role> loadRoles() {
        Map<String, Role> roleByCode = new LinkedHashMap<>();
        for (String code : List.of("ADMIN", "PM", "SUPPORT", "DEVELOPER", "QA", "DEPLOYMENT")) {
            roleByCode.put(code, roles.findByCode(code)
                    .orElseThrow(() -> new IllegalStateException(
                            "role '" + code + "' is missing — B-001's seed did not run before the fixture loader")));
        }
        return roleByCode;
    }

    private Map<String, User> createUsers(Map<String, Role> roleByCode) {
        // Argon2id is deliberately expensive per call — hashed once and shared
        // across all 18 fixture users rather than 18 times, since none of them
        // needs a distinct password today. dev-noauth (A-012) stands in for
        // login until A-020 lands; this hash exists only so the column is never
        // empty and a future real login attempt fails the password check rather
        // than finding nothing to compare against.
        String sharedHash = passwordEncoder.encode(FIXTURE_PASSWORD);

        Map<String, User> byUsername = new LinkedHashMap<>();
        for (UserSpec spec : USERS) {
            User user = new User();
            user.setEmpCode("B7-" + spec.empSuffix());
            user.setUsername(spec.username());
            user.setEmail(spec.username() + "@edunext.example");
            user.setPasswordHash(sharedHash);
            user.setFullName(spec.fullName());
            user.setRole(roleByCode.get(spec.roleCode()));
            user.setDepartment(spec.department());
            user.setDesignation(spec.designation());
            user.setMustChangePassword(true);
            byUsername.put(spec.username(), users.save(user));
        }
        return byUsername;
    }

    /** PMs report to the Admin; everyone else on a project reports to that project's PM. */
    private void assignManagers(Map<String, User> userByUsername) {
        User admin = userByUsername.get("priya.nair");
        for (ProjectSpec spec : PROJECTS) {
            User pm = userByUsername.get(spec.pmUsername());
            pm.setReportingManagerId(admin.getId());
            users.save(pm);
            for (String username : spec.memberUsernames()) {
                User member = userByUsername.get(username);
                // Tanvi (deployment) sits on two projects; the first assignment
                // wins, which is fine — a reporting line is one edge, not a set.
                if (member.getReportingManagerId() == null) {
                    member.setReportingManagerId(pm.getId());
                    users.save(member);
                }
            }
        }
    }

    private Map<String, Project> createProjects(Map<String, User> userByUsername) {
        Map<String, Project> byCode = new LinkedHashMap<>();
        for (ProjectSpec spec : PROJECTS) {
            Project project = new Project();
            project.setProjectCode(spec.code());
            project.setName(spec.name());
            project.setColourTag(spec.colourTag());
            project.setManagerId(userByUsername.get(spec.pmUsername()).getId());
            project.setStatus("ACTIVE");
            byCode.put(spec.code(), projects.save(project));
        }
        return byCode;
    }

    private Map<Long, List<Long>> createProjectMembers(Map<String, Project> projectByCode,
                                                         Map<String, User> userByUsername) {
        Map<Long, List<Long>> memberIdsByProject = new LinkedHashMap<>();
        for (ProjectSpec spec : PROJECTS) {
            Project project = projectByCode.get(spec.code());
            List<Long> memberIds = new ArrayList<>();

            User pm = userByUsername.get(spec.pmUsername());
            addMember(project, pm, "PM");
            memberIds.add(pm.getId());

            for (String username : spec.memberUsernames()) {
                User member = userByUsername.get(username);
                addMember(project, member, member.getRole().getCode());
                memberIds.add(member.getId());
            }
            memberIdsByProject.put(project.getId(), memberIds);
        }
        return memberIdsByProject;
    }

    private void addMember(Project project, User user, String roleInProject) {
        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUserId(user.getId());
        member.setRoleInProject(roleInProject);
        projectMembers.save(member);
    }

    private Map<String, Client> createClients(Map<String, User> userByUsername) {
        Map<String, Client> byCode = new LinkedHashMap<>();
        for (ClientSpec spec : CLIENTS) {
            Client client = new Client();
            client.setClientCode(spec.code());
            client.setName(spec.name());
            client.setIndustry(spec.industry());
            client.setSupportPlan(spec.supportPlan());
            client.setStatus("ACTIVE");
            // The account manager is whichever PM owns the client's first
            // (primary) project — good enough for a fixture; the client
            // master's own create flow (B-026) will let this be edited.
            String firstProjectCode = spec.projectCodes().get(0);
            PROJECTS.stream()
                    .filter(p -> p.code().equals(firstProjectCode))
                    .findFirst()
                    .ifPresent(p -> client.setAccountManagerId(userByUsername.get(p.pmUsername()).getId()));
            byCode.put(spec.code(), clients.save(client));
        }
        return byCode;
    }

    private Map<Long, Long> createClientContacts(Map<String, Client> clientByCode) {
        Map<Long, Long> primaryContactByClient = new LinkedHashMap<>();
        for (ClientSpec spec : CLIENTS) {
            Client client = clientByCode.get(spec.code());
            for (ContactSpec contactSpec : spec.contacts()) {
                ClientContact contact = new ClientContact();
                contact.setClient(client);
                contact.setName(contactSpec.name());
                contact.setDesignation(contactSpec.designation());
                contact.setEmail(contactSpec.name().toLowerCase(java.util.Locale.ROOT).replace(' ', '.')
                        + "@" + spec.code().toLowerCase(java.util.Locale.ROOT) + ".example");
                contact.setPrimary(contactSpec.primary());
                ClientContact saved = clientContacts.save(contact);
                if (contactSpec.primary()) {
                    primaryContactByClient.put(client.getId(), saved.getId());
                }
            }
        }
        return primaryContactByClient;
    }

    private Map<Long, List<Long>> mapClientsToProjects(Map<String, Client> clientByCode,
                                                         Map<String, Project> projectByCode) {
        Map<Long, List<Long>> clientsByProject = new LinkedHashMap<>();
        for (ClientSpec spec : CLIENTS) {
            Client client = clientByCode.get(spec.code());
            for (int i = 0; i < spec.projectCodes().size(); i++) {
                Project project = projectByCode.get(spec.projectCodes().get(i));

                ClientProjectId id = new ClientProjectId();
                id.setClientId(client.getId());
                id.setProjectId(project.getId());

                ClientProject clientProject = new ClientProject();
                clientProject.setId(id);
                clientProject.setDefault(i == 0);
                clientProjects.save(clientProject);

                clientsByProject.computeIfAbsent(project.getId(), k -> new ArrayList<>()).add(client.getId());
            }
        }
        return clientsByProject;
    }

    private void createSlaPolicies() {
        RESOLUTION_HOURS_BY_LEVEL.forEach((level, resolutionHrs) -> {
            SlaPolicy policy = new SlaPolicy();
            policy.setLevel(level);
            policy.setResolutionHrs(BigDecimal.valueOf(resolutionHrs));
            policy.setResponseHrs(BigDecimal.valueOf(Math.max(1, resolutionHrs / 2)));
            policy.setEscalateToL1(true);
            policy.setEscalateToL2("CRITICAL".equals(level));
            slaPolicies.save(policy);
        });
    }
}
