package com.edunext.edutrack.api.feature.fixtures.onboarding;

import java.util.List;

/**
 * B-101 · the onboarding fixture corpus, transcribed from
 * {@code docs/prototype/onboarding.html}.
 *
 * <p><b>This file is data, not behaviour.</b> Every constant below is a
 * transcription of a literal in the prototype — {@code TPL_STEPS},
 * {@code BIO_STEPS}, {@code PRODUCTS}, {@code CLIENTS} and the assignments that
 * follow them. {@link OnboardingFixture} is the only thing that reads it and
 * the only thing that decides what a row looks like.
 *
 * <p>Keeping the two apart is the point of the split. {@code
 * PHASE-2-BUILD-PLAN.md} §2 says the prototype's journey "becomes the OB1
 * fixture corpus verbatim", so the value of the corpus is that it is
 * recognisably the design's own data rather than a second invented one. A
 * reader checking that claim reads this file against the prototype and needs to
 * understand nothing about JDBC to do it.
 *
 * <h2>What is transcribed, and what could not be</h2>
 *
 * <p>The prototype models more than the landed schema can hold. Rather than
 * invent tables, each gap is named here so nobody has to diff two files to find
 * them:
 *
 * <ul>
 *   <li><b>PAN is dropped.</b> {@code ob_clients} stores it as
 *       {@code pan_ciphertext} + {@code pan_blind_index}, and A-101's own
 *       migration says neither column is written before A-113. Writing the
 *       prototype's plaintext PAN would mean inventing a blind index nothing
 *       can reproduce, so both stay NULL and B-102's duplicate guard has
 *       nothing here to trip over.</li>
 *   <li><b>Payments are dropped</b> — deliberately, and not by this task. v1.1
 *       removed financial tracking; A-102 records {@code ob_payments} as
 *       dropped with it.</li>
 *   <li><b>Attachments are dropped.</b> {@code ob_attachments} is A-102 and has
 *       not landed. The prototype's file names are transcribed onto
 *       {@link ClientSpec#attachmentNames()} anyway, unused, so B-107 can seed
 *       them without re-reading the prototype.</li>
 *   <li><b>Prerequisites, client accounts, sign-offs, notifications and CSAT
 *       are dropped</b> — B-124/B-125, A-125, A-107 and B-119 respectively.
 *       None of their tables exist yet.</li>
 *   <li><b>{@code city} and {@code payMode} have no column.</b> City is folded
 *       into {@code address}, which is where the prototype's own address string
 *       already carries it. Payment mode goes with the payments.</li>
 * </ul>
 *
 * <h2>Dates are serials, and the anchor is the load date</h2>
 *
 * <p>The prototype numbers days from 1 = 01 Jun 2026, with {@code TODAY_SER =
 * 81} = 20 Aug 2026, and every date here is one of those serials.
 * {@link OnboardingFixture} anchors serial {@value #TODAY_SERIAL} to the day the
 * fixture is loaded rather than to 20 Aug 2026.
 *
 * <p>That is the one place this corpus is deliberately not verbatim, and it is
 * the difference between a demo and a screenshot. Half the states the corpus
 * exists to exercise are relative to now — a step due today, a step breached
 * eleven days ago, a clock paused since Tuesday. Pinned to a date in the past,
 * every one of them reads as "long overdue" and the SLA scanner, the amber
 * threshold and the RAG board have nothing left to distinguish. The <em>shape</em>
 * — which step is where, how far each journey has run, which one is late — is
 * exactly the prototype's; only the origin moves.
 */
final class OnboardingFixtureData {

    /** The prototype's {@code TODAY_SER}: the serial standing for "today". */
    static final int TODAY_SERIAL = 81;

    private OnboardingFixtureData() {
    }

    // ══════════════════════════════════════════════════════════════════════
    // Users
    // ══════════════════════════════════════════════════════════════════════

    /**
     * One of the prototype's demo staff users.
     *
     * @param key              the prototype's own id ({@code u1}…{@code u8}) — every
     *                         other constant here refers to a user by it
     * @param fullName         verbatim
     * @param username         from the prototype's email local part
     * @param obRole           the module plan §3 role
     * @param platformRoleCode the phase-1 {@code roles.code} this user is parked
     *                         on until A-109 and A-117 land the six real
     *                         onboarding roles
     * @param department       free text, so the resource list looks inhabited
     */
    record UserSpec(String key, String fullName, String username, String obRole,
                    String platformRoleCode, String department) {
    }

    /**
     * The prototype's {@code USERS}, minus {@code u3}.
     *
     * <p><b>{@code u3} is Priya Nair, and B-007 already created her.</b> The
     * prototype's step owner and the ticket corpus's Head of Delivery are the
     * same name at the same company, so this corpus reuses that row rather than
     * putting a second Priya Nair in the owner dropdown of every screen the demo
     * exists for. Same argument B-007 made for reusing D-004's {@code CRM} /
     * {@code PAY} / {@code WEB} project codes: one fixture world, not two that
     * happen to share a database.
     *
     * <p><b>{@code platformRoleCode} is a placeholder and is visible as one.</b>
     * The six onboarding roles (OB Admin, Onboarding Manager, Step Owner, Sales,
     * Finance, Viewer) arrive with A-109's {@code user_module_access} and
     * A-117's admin screen; until then {@code users.role_id} can only hold a
     * phase-1 code. Each user's real role is written to
     * {@code users.designation}, so the demo reads correctly on screen and the
     * re-point, once those roles exist, is a lookup rather than a guess. None of
     * these users is a member of any project, so the phase-1 role grants them
     * nothing at all in the ticketing module.
     */
    static final List<UserSpec> USERS = List.of(
            new UserSpec("u1", "Anita Rao", "anita.rao", "OB_ADMIN", "ADMIN", "Onboarding"),
            new UserSpec("u2", "Vikram Mehta", "vikram.mehta", "MANAGER", "PM", "Onboarding"),
            new UserSpec("u4", "Rohan Gupta", "rohan.gupta", "SALES", "SUPPORT", "Sales"),
            new UserSpec("u5", "Meera Iyer", "meera.iyer", "FINANCE", "SUPPORT", "Finance"),
            new UserSpec("u6", "Suresh Kumar", "suresh.kumar", "VIEWER", "QA", "Management"),
            new UserSpec("u7", "Kavya Sharma", "kavya.sharma", "STEP_OWNER", "DEVELOPER", "Implementation"),
            new UserSpec("u8", "Nikhil Joshi", "nikhil.joshi", "STEP_OWNER", "DEVELOPER", "Implementation"));

    /** The prototype's {@code u3} — B-007's existing {@code users} row, reused. See {@link #USERS}. */
    static final String SHARED_USER_KEY = "u3";

    /** {@code users.username} of the B-007 row {@link #SHARED_USER_KEY} resolves to. */
    static final String SHARED_USERNAME = "priya.nair";

    /** Readable labels for {@link UserSpec#obRole()}, written to {@code users.designation}. */
    static String obRoleLabel(String obRole) {
        return switch (obRole) {
            case "OB_ADMIN" -> "OB Admin";
            case "MANAGER" -> "Onboarding Manager";
            case "STEP_OWNER" -> "Step Owner";
            case "SALES" -> "Sales";
            case "FINANCE" -> "Finance";
            case "VIEWER" -> "Viewer (Management)";
            default -> throw new IllegalArgumentException("unknown onboarding role: " + obRole);
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // Products
    // ══════════════════════════════════════════════════════════════════════

    /**
     * One row of {@code ob_products}.
     *
     * @param key  the prototype's own id ({@code p1}…{@code p4})
     * @param code {@code ob_products.code} — the prototype has no code, only a
     *             name and an emoji, so this is derived from the name
     * @param name verbatim
     */
    record ProductSpec(String key, String code, String name) {
    }

    /** The prototype's {@code PRODUCTS}. The emoji has no column and is dropped. */
    static final List<ProductSpec> PRODUCTS = List.of(
            new ProductSpec("p1", "ERP", "EduTrack ERP"),
            new ProductSpec("p2", "BIOMETRIC", "Biometric Attendance"),
            new ProductSpec("p3", "PARENT_PORTAL", "Parent Portal"),
            new ProductSpec("p4", "EXAM", "Exam Module"));

    // ══════════════════════════════════════════════════════════════════════
    // Journey templates
    // ══════════════════════════════════════════════════════════════════════

    /**
     * One row of {@code ob_journey_template_steps} plus its items and docs.
     *
     * @param name            verbatim
     * @param dependsOnIndex  the prototype's {@code dep} — the index within this
     *                        template of the step this one waits for, or
     *                        {@code null} for a step that activates in parallel
     * @param tatDays         the prototype's {@code tat}. <b>Working days.</b>
     *                        A-103 changed the unit in v1.2 and the prototype's
     *                        numbers (1–5) are already days, not hours
     * @param ownerKey        a {@link UserSpec#key()}
     * @param requiresSignoff the prototype's {@code signoff}
     * @param requiredDocs    the prototype's {@code docs} — every one of them is
     *                        required, which is why there is no flag here
     * @param items           the prototype's {@code subs}, the Task List
     */
    record StepSpec(String name, Integer dependsOnIndex, int tatDays, String ownerKey,
                    boolean requiresSignoff, List<String> requiredDocs, List<String> items) {
    }

    /**
     * One row of {@code ob_journey_templates} — one <em>version</em> of one
     * Module Service.
     *
     * @param key                  the prototype's own id ({@code t1}…{@code t3})
     * @param name                 verbatim
     * @param version              verbatim
     * @param productKey           a {@link ProductSpec#key()}
     * @param active               verbatim; at most one per product, which the
     *                             generated {@code active_key} column enforces
     * @param sequence             the service order (A-103, plan §5.5). Not in
     *                             the prototype, which renders templates in array
     *                             order; that order is what is transcribed
     * @param dependsOnTemplateKey the prototype's template-level {@code dep} —
     *                             the cross-product service dependency
     */
    record TemplateSpec(String key, String name, int version, String productKey, boolean active,
                        int sequence, String dependsOnTemplateKey, List<StepSpec> steps) {
    }

    /**
     * The prototype's {@code TPL_STEPS} — eight steps, twenty-four Task List
     * items, four required documents, three sign-off gates, TATs 1–5 working
     * days. {@code PHASE-2-BUILD-PLAN.md} §2 adopts this as the seeded default.
     *
     * <p><b>Four documents, where the build plan's summary says five.</b> The
     * prototype attaches one to Requirements confirmation, two to Data migration
     * and one to Admin & user training; the other five steps carry none. The
     * plan's count is off by one, which is worth knowing because that sentence
     * is what most readers will check the corpus against. Transcribed as it is,
     * and pinned by {@code OnboardingFixtureScheduleTest}.
     */
    static final List<StepSpec> STANDARD_SAAS_STEPS = List.of(
            new StepSpec("Kickoff call", null, 1, "u4", false, List.of(),
                    List.of("Intro call done & teams mapped",
                            "Scope re-confirmed with SPOC",
                            "Kickoff MoM shared")),
            new StepSpec("Requirements confirmation", 0, 2, "u3", true,
                    List.of("Signed requirement sheet"),
                    List.of("Requirement sheet filled",
                            "Custom fields & reports listed",
                            "Gaps flagged to product team")),
            new StepSpec("Account & environment setup", 1, 3, "u7", false, List.of(),
                    List.of("Tenant provisioned",
                            "Admin accounts created",
                            "SSO / custom domain configured")),
            new StepSpec("Data migration", 2, 5, "u7", false,
                    List.of("Migration source file", "Validation report"),
                    List.of("Source data received",
                            "Validation errors resolved",
                            "Dry-run migration verified",
                            "Client confirmed sample records")),
            new StepSpec("Configuration & branding", 2, 3, "u3", false, List.of(),
                    List.of("Logo & colours applied",
                            "Notice / report templates set",
                            "Roles & permissions configured")),
            new StepSpec("Admin & user training", 4, 2, "u3", false,
                    List.of("Attendance sheet"),
                    List.of("Admin training conducted",
                            "End-user training conducted",
                            "Training material shared")),
            new StepSpec("UAT & issue closure", 5, 3, "u7", true, List.of(),
                    List.of("UAT scenarios executed",
                            "All logged issues closed",
                            "UAT report shared")),
            new StepSpec("Go-live sign-off", 6, 1, "u2", true, List.of(),
                    List.of("Go-live checklist reviewed",
                            "Support handover completed")));

    /** The prototype's {@code BIO_STEPS} — the v1.1 second product's journey. */
    static final List<StepSpec> BIOMETRIC_STEPS = List.of(
            new StepSpec("Kickoff & site survey", null, 1, "u4", false, List.of(),
                    List.of("Sites listed & device counts confirmed",
                            "Network readiness checked")),
            new StepSpec("Device dispatch & installation", 0, 5, "u7", false,
                    List.of("Delivery challan"),
                    List.of("Devices dispatched",
                            "Devices mounted & powered",
                            "Connectivity verified")),
            new StepSpec("Device configuration & ERP sync", 1, 3, "u7", false, List.of(),
                    List.of("Devices registered in ERP",
                            "Sync schedule configured")),
            new StepSpec("User enrollment", 2, 3, "u3", false,
                    List.of("Enrollment completion sheet"),
                    List.of("Staff enrolled", "Students enrolled", "Exceptions resolved")),
            new StepSpec("Go-live sign-off", 3, 1, "u2", true, List.of(),
                    List.of("Attendance flowing to ERP", "Support handover completed")));

    /**
     * The prototype's {@code TEMPLATES}.
     *
     * <p><b>Version 2 of the ERP service does not exist, and that is the
     * prototype's own arrangement rather than an omission here.</b> It declares
     * "Standard SaaS Onboarding" at version 3 and "Enterprise (with data
     * migration audit)" at version 1, both on the ERP product, one of them
     * active. A-103 reads a row as one version of one product's service, so the
     * two land as versions 1 and 3 with nothing at 2. Harmless:
     * {@code version} is a label that {@code ob_journeys.template_id} pins, not
     * a dense sequence anything counts along.
     *
     * <p>Retired first, active second, dependency last — {@link OnboardingFixture}
     * inserts in this order and {@code fk_ob_journey_templates_depends_on} needs
     * {@code t1} to exist before {@code t3} names it.
     */
    static final List<TemplateSpec> TEMPLATES = List.of(
            new TemplateSpec("t2", "Enterprise (with data migration audit)", 1, "p1", false, 1, null,
                    STANDARD_SAAS_STEPS),
            new TemplateSpec("t1", "Standard SaaS Onboarding", 3, "p1", true, 1, null,
                    STANDARD_SAAS_STEPS),
            new TemplateSpec("t3", "Biometric Device Rollout", 2, "p2", true, 2, "t1",
                    BIOMETRIC_STEPS));

    // ══════════════════════════════════════════════════════════════════════
    // Clients and their journeys
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A SPOC.
     *
     * <p>{@code whatsappOptIn} is B-103's consent flag. Nothing sends WhatsApp
     * this phase, but consent cannot be backfilled — a SPOC boarded without it
     * has to be re-approached before a single message goes out — so the corpus
     * carries a mix rather than a column of zeroes that would let a
     * consent-blind query pass.
     */
    record ContactSpec(String name, String email, String phone, boolean primary, boolean whatsappOptIn) {
    }

    /** One row of {@code ob_client_applications}: what the client bought. */
    record ApplicationSpec(String productKey, String licenseType, int units) {
    }

    /**
     * One row of {@code ob_journeys} and the steps under it.
     *
     * @param templateKey       a {@link TemplateSpec#key()}
     * @param currentIndex      the prototype's {@code mkSteps(i, …)}: every step
     *                          before this one is DONE, this one is in flight,
     *                          the rest PENDING. {@code steps.size()} means the
     *                          whole journey is complete
     * @param startSerial       the prototype's {@code opts.start}
     * @param gateOpen          the prerequisite gate (plan §5.3). One client's is
     *                          LOCKED
     * @param heldByTemplateKey set when this journey waits on another service of
     *                          the same client — the prototype's {@code heldJourney}
     * @param currentStatus     an override for the in-flight step's status:
     *                          {@code WAITING_ON_CLIENT} or {@code BLOCKED}.
     *                          {@code null} means {@code IN_PROGRESS}
     * @param blockedNote       the prototype's {@code opts.block}
     * @param signoffRequested  the prototype's {@code signoffState:"REQUESTED"}
     */
    record JourneySpec(String templateKey, int currentIndex, int startSerial, boolean gateOpen,
                       String heldByTemplateKey, String currentStatus, String blockedNote,
                       boolean signoffRequested) {

        static JourneySpec of(String templateKey, int currentIndex, int startSerial) {
            return new JourneySpec(templateKey, currentIndex, startSerial, true, null, null, null, false);
        }

        JourneySpec waitingOnClient() {
            return new JourneySpec(templateKey, currentIndex, startSerial, gateOpen, heldByTemplateKey,
                    "WAITING_ON_CLIENT", blockedNote, signoffRequested);
        }

        JourneySpec blocked(String note) {
            return new JourneySpec(templateKey, currentIndex, startSerial, gateOpen, heldByTemplateKey,
                    "BLOCKED", note, signoffRequested);
        }

        JourneySpec withSignoffRequested() {
            return new JourneySpec(templateKey, currentIndex, startSerial, gateOpen, heldByTemplateKey,
                    currentStatus, blockedNote, true);
        }

        JourneySpec gateLocked() {
            return new JourneySpec(templateKey, currentIndex, startSerial, false, heldByTemplateKey,
                    currentStatus, blockedNote, signoffRequested);
        }

        JourneySpec heldBy(String otherTemplateKey) {
            return new JourneySpec(templateKey, currentIndex, startSerial, gateOpen, otherTemplateKey,
                    currentStatus, blockedNote, signoffRequested);
        }
    }

    /**
     * One client, with everything hanging off it.
     *
     * @param key             the prototype's own id ({@code c1}…{@code c8})
     * @param licenseType     the client-level licence label, e.g. "Enterprise · Annual"
     * @param onboardSerial   the prototype's {@code onboardDate}
     * @param liveSerial      the prototype's {@code liveAt}, or {@code null}
     * @param attachmentNames transcribed and <b>unused</b> — see the class javadoc
     */
    record ClientSpec(String key, String name, String description, String address, String salesUserKey,
                      String createdByUserKey, String licenseType, int onboardSerial, String status,
                      Integer liveSerial, List<ContactSpec> contacts, List<ApplicationSpec> applications,
                      String requirements, List<String> attachmentNames, List<JourneySpec> journeys) {
    }

    /**
     * The prototype's {@code CLIENTS}, with the journeys its v1.1 block attaches
     * to each.
     *
     * <p><b>Eight, where the backlog line says six.</b> Seeding all of them is
     * both the more literal reading of "verbatim" and the more useful corpus:
     * the two a count of six would have dropped are the ones carrying the locked
     * prerequisite gate and the blocked step, and a corpus without those cannot
     * exercise the gate or the block. Two extra clients cost nothing.
     *
     * <p><b>Every journey needs a matching purchase.</b>
     * {@code fk_ob_journeys_application} points straight into
     * {@code ob_client_applications}, so a Biometric journey is unrepresentable
     * without a Biometric purchase. The prototype attaches a second journey to
     * GreenValley, Horizon and Nalanda without adding that product to their
     * {@code apps}; the purchase rows here are that consequence, not an
     * embellishment.
     */
    static final List<ClientSpec> CLIENTS = List.of(
            new ClientSpec("c1", "GreenValley International School",
                    "K-12 chain, 3 campuses, moving off spreadsheets.",
                    "14 Ridge Rd, Aundh, Pune 411007", "u4", "u4",
                    "Enterprise · Annual", 12, "LIVE", 68,
                    List.of(new ContactSpec("Deepa Kulkarni", "deepa@greenvalley.edu.in",
                            "+91 98220 11223", true, true)),
                    List.of(new ApplicationSpec("p1", "Enterprise", 120),
                            new ApplicationSpec("p3", "Add-on", 2400),
                            new ApplicationSpec("p2", "Add-on", 3)),
                    "Migrate 6 yrs of student records; SSO with Google Workspace; "
                            + "Marathi + English notices.",
                    List.of("PO_GreenValley.pdf", "MSA_signed.pdf", "Migration_mapping.xlsx"),
                    List.of(JourneySpec.of("t1", 8, 12),
                            JourneySpec.of("t3", 5, 40))),

            new ClientSpec("c2", "Sunrise EdTech Pvt Ltd",
                    "Test-prep startup, 40 counsellors.",
                    "77 Residency Rd, Bengaluru 560025", "u4", "u4",
                    "Professional · Annual", 58, "ONBOARDING", null,
                    List.of(new ContactSpec("Arjun Shetty", "arjun@sunrise-ed.com",
                            "+91 98450 77812", true, true)),
                    List.of(new ApplicationSpec("p1", "Professional", 40)),
                    "Bulk import of 12k leads; WhatsApp counselling follow-ups.",
                    List.of("PO_Sunrise.pdf"),
                    List.of(JourneySpec.of("t1", 3, 58))),

            new ClientSpec("c3", "Horizon Academy",
                    "CBSE senior secondary, 2,100 students.",
                    "5-9-22 Banjara Hills, Hyderabad 500034", "u1", "u1",
                    "Professional · Annual", 64, "ONBOARDING", null,
                    List.of(new ContactSpec("Fatima Begum", "fatima@horizon.ac.in",
                            "+91 90000 44556", true, false)),
                    List.of(new ApplicationSpec("p1", "Professional", 60),
                            new ApplicationSpec("p4", "Add-on", 1),
                            new ApplicationSpec("p2", "Add-on", 2)),
                    "Exam-hall seating plans; report-card templates in Telugu.",
                    List.of("PO_Horizon.pdf", "Report_card_samples.zip"),
                    List.of(JourneySpec.of("t1", 5, 64),
                            JourneySpec.of("t3", 0, 65).heldBy("t1"))),

            new ClientSpec("c4", "Bluebell Public School",
                    "Single campus, first ERP purchase.",
                    "C-31 Malviya Nagar, Jaipur 302017", "u4", "u4",
                    "Starter · Annual", 71, "ONBOARDING", null,
                    List.of(new ContactSpec("Nikhil Saxena", "nikhil@bluebell.edu.in",
                            "+91 94140 22110", true, true)),
                    List.of(new ApplicationSpec("p1", "Starter", 15)),
                    "Fee reminders on WhatsApp; Hindi UI labels where available.",
                    List.of("PO_Bluebell.pdf"),
                    List.of(JourneySpec.of("t1", 1, 71).waitingOnClient())),

            new ClientSpec("c5", "Nalanda Group of Institutions",
                    "3 colleges + 2 schools under one trust.",
                    "Boring Rd, Patna 800001", "u1", "u1",
                    "Enterprise · 3-year", 73, "ONBOARDING", null,
                    List.of(new ContactSpec("Ritu Verma", "ritu@nalandagroup.org",
                                    "+91 98350 66778", true, true),
                            new ContactSpec("A. K. Singh", "aksingh@nalandagroup.org",
                                    "+91 94310 11224", false, false)),
                    List.of(new ApplicationSpec("p1", "Enterprise", 200),
                            new ApplicationSpec("p3", "Add-on", 9000),
                            new ApplicationSpec("p4", "Add-on", 1),
                            new ApplicationSpec("p2", "Add-on", 5)),
                    "Phased rollout college-by-college; UGC report formats.",
                    List.of("MSA_Nalanda.pdf", "Rollout_plan_v2.pptx"),
                    List.of(JourneySpec.of("t1", 2, 73),
                            JourneySpec.of("t3", 0, 74).heldBy("t1"))),

            new ClientSpec("c6", "Cambridge Heights School",
                    "IB curriculum, high-touch onboarding.",
                    "Linking Rd, Bandra W, Mumbai 400050", "u4", "u4",
                    "Professional · Annual", 50, "ONBOARDING", null,
                    List.of(new ContactSpec("Sana Qureshi", "sana@cambridgeheights.in",
                            "+91 98200 33445", true, true)),
                    List.of(new ApplicationSpec("p1", "Professional", 85)),
                    "IB grade descriptors; UAT with 8 teachers before go-live.",
                    List.of("PO_CambridgeHeights.pdf", "UAT_checklist.xlsx"),
                    List.of(JourneySpec.of("t1", 6, 50).withSignoffRequested())),

            new ClientSpec("c7", "Little Scholars Preschool",
                    "Preschool chain, 6 centres.",
                    "MG Rd, Kochi 682016", "u4", "u4",
                    "Starter · Annual", 80, "ONBOARDING", null,
                    List.of(new ContactSpec("Divya Menon", "divya@littlescholars.in",
                            "+91 98470 88990", true, true)),
                    List.of(new ApplicationSpec("p1", "Starter", 12)),
                    "Photo-sharing consent workflow for parents.",
                    List.of(),
                    List.of(JourneySpec.of("t1", 0, 80).gateLocked())),

            new ClientSpec("c8", "Trinity College of Commerce",
                    "UG college, 4,000 students.",
                    "Anna Salai, Chennai 600002", "u1", "u1",
                    "Professional · Annual", 55, "ONBOARDING", null,
                    List.of(new ContactSpec("George Thomas", "george@trinitycc.ac.in",
                            "+91 98410 55667", true, false)),
                    List.of(new ApplicationSpec("p1", "Professional", 70),
                            new ApplicationSpec("p4", "Add-on", 1)),
                    "Semester exam schedules; college branding on portal.",
                    List.of("PO_Trinity.pdf", "Brand_guidelines.pdf"),
                    List.of(JourneySpec.of("t1", 4, 55)
                            .blocked("Branding assets pending from client's design vendor"))));

    // ══════════════════════════════════════════════════════════════════════
    // The overrides the prototype applies after building CLIENTS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * An answer the prototype writes over the default the walk produced.
     *
     * <p>The default is {@code mkStepsFrom}'s: every item of a completed step
     * answers True, the first item of the in-flight step answers True, the rest
     * are unanswered. These are the exceptions.
     *
     * @param answer {@code null} re-opens an item the default had answered
     */
    record ItemAnswerSpec(String clientKey, String templateKey, int stepIndex, int itemIndex,
                          Boolean answer, String remark) {
    }

    /** The prototype's "seed sub-category answers on in-flight steps" block. */
    static final List<ItemAnswerSpec> ITEM_ANSWERS = List.of(
            new ItemAnswerSpec("c2", "t1", 3, 1, false,
                    "214 rows failing phone validation — corrected CSV awaited from client"),
            new ItemAnswerSpec("c2", "t1", 3, 2, null, null),
            new ItemAnswerSpec("c8", "t1", 4, 0, false,
                    "Awaiting final brand assets from client's design vendor"),
            new ItemAnswerSpec("c8", "t1", 4, 2, true, null),
            new ItemAnswerSpec("c6", "t1", 6, 0, true, null),
            new ItemAnswerSpec("c6", "t1", 6, 1, true, null),
            new ItemAnswerSpec("c6", "t1", 6, 2, true, null),
            new ItemAnswerSpec("c3", "t1", 5, 1, null, null));

    /**
     * One row of {@code ob_step_communications}.
     *
     * @param authorKey     a {@link UserSpec#key()} for a staff entry,
     *                      {@code null} for a client or system one
     * @param authorType    {@code STAFF}, {@code CLIENT} or {@code SYSTEM}
     * @param clientVisible the prototype's {@code dir}: {@code INT} is an
     *                      internal note, everything else reached the client
     * @param daySerial     the prototype's date, as a serial
     * @param minuteOfDay   the prototype's time, in the org calendar's zone
     */
    record CommunicationSpec(String clientKey, String templateKey, int stepIndex, int daySerial,
                             int minuteOfDay, String entryType, String authorType, String authorKey,
                             boolean clientVisible, String body) {
    }

    private static CommunicationSpec staff(String clientKey, String templateKey, int stepIndex, int daySerial,
                                           int minuteOfDay, String entryType, String authorKey,
                                           boolean clientVisible, String body) {
        return new CommunicationSpec(clientKey, templateKey, stepIndex, daySerial, minuteOfDay, entryType,
                "STAFF", authorKey, clientVisible, body);
    }

    private static CommunicationSpec system(String clientKey, String templateKey, int stepIndex, int daySerial,
                                            int minuteOfDay, String body) {
        return new CommunicationSpec(clientKey, templateKey, stepIndex, daySerial, minuteOfDay, "SYSTEM",
                "SYSTEM", null, false, body);
    }

    private static CommunicationSpec fromClient(String clientKey, String templateKey, int stepIndex,
                                                int daySerial, int minuteOfDay, String entryType, String body) {
        return new CommunicationSpec(clientKey, templateKey, stepIndex, daySerial, minuteOfDay, entryType,
                "CLIENT", null, true, body);
    }

    /**
     * The prototype's "seed a few communications" block, plus the escalation
     * entry its v1.1 section appends.
     *
     * <p>The prototype's {@code type} vocabulary is wider than A-106's:
     * {@code NOTE} becomes {@code COMMENT}, and the one {@code WHATSAPP} entry
     * becomes {@code SYSTEM}, which is what it actually is — an alert the
     * scanner delivered, not a person writing.
     */
    static final List<CommunicationSpec> COMMUNICATIONS = List.of(
            staff("c2", "t1", 3, 77, 10 * 60 + 12, "CALL", "u7", true,
                    "Walked Arjun through the lead-import template; he will resend the corrected CSV."),
            staff("c2", "t1", 3, 79, 9 * 60 + 30, "EMAIL", "u7", true,
                    "Reminder sent — migration file still has 214 rows failing phone validation."),
            system("c2", "t1", 3, 80, 16 * 60 + 5,
                    "TAT breach alert delivered to owner and manager."),
            fromClient("c2", "t1", 3, 80, 17 * 60 + 40, "ESCALATION",
                    "Migration delay is holding our launch date — please expedite."),

            staff("c6", "t1", 6, 79, 14 * 60 + 20, "MEETING", "u7", true,
                    "UAT round 2 with 8 teachers — 3 minor issues logged, all fixed."),
            system("c6", "t1", 6, 80, 11 * 60,
                    "Sign-off link sent to Sana Qureshi (email + WhatsApp)."),

            staff("c4", "t1", 1, 78, 12 * 60 + 40, "CALL", "u3", true,
                    "Requirement sheet shared; Nikhil needs trustee approval before confirming."),
            staff("c4", "t1", 1, 78, 12 * 60 + 45, "COMMENT", "u3", false,
                    "Clock paused — waiting on client for signed requirement sheet."),

            staff("c8", "t1", 4, 75, 15 * 60 + 10, "EMAIL", "u3", true,
                    "Design vendor is travelling; logo files expected by 22 Aug."),
            staff("c8", "t1", 4, 75, 15 * 60 + 20, "COMMENT", "u3", false,
                    "Blocked — cannot theme the portal without final brand assets."));

    /**
     * One row of {@code ob_client_escalations}: the prototype's {@code clientEsc},
     * raised from the portal by Sunrise EdTech's SPOC and still open.
     */
    record EscalationSpec(String clientKey, String templateKey, int stepIndex, int daySerial,
                          int minuteOfDay, String comment) {
    }

    static final List<EscalationSpec> ESCALATIONS = List.of(
            new EscalationSpec("c2", "t1", 3, 80, 17 * 60 + 40,
                    "Migration delay is holding our launch date — please expedite."));
}
