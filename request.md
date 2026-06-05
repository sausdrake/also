You are an expert software architect and full-stack developer. I need your help to organize and execute a large-scale refactoring and development task for an online car auction system.

## CONTEXT

I have created two files that you MUST read and analyze:

1. **`script.txt`** - Contains detailed user scenarios describing how the system should work from the perspective of different actors (GUEST, USER, DIAGNOSTIC, ADMIN). These scenarios define the expected behavior of the system.

2. **`tasks.txt`** - Contains a comprehensive list of technical tasks that need to be completed for both backend (Spring Boot) and frontend (React/TypeScript).

## YOUR TASK

### PHASE 1: ANALYSIS (Read and understand both files)

First, analyze `script.txt` to understand the business logic and expected behavior of the system. Pay attention to:
- All user roles and their permissions
- The complete flow: user creates diagnostic request → diagnostician takes it → fills inspection report → admin approves → creates auction → bidding → WebSocket updates
- Admin functionality (template builder, auction management, bulk operations)
- Media upload flow (presigned URLs, cloud storage)
- Edge cases (unauthenticated users, expired auctions, rejected reports)

Then, analyze `tasks.txt` to understand the technical requirements.

### PHASE 2: CRITICAL ANALYSIS & IMPROVEMENTS

Based on your analysis of `script.txt` and `tasks.txt`:

1. **Identify gaps** - Are there any scenarios in `script.txt` that are NOT covered by tasks in `tasks.txt`? List them.
2. **Identify inconsistencies** - Are there any tasks that contradict the scenarios?
3. **Suggest improvements** - What could be done better? (architecture, security, performance, UX)
4. **Group tasks by complexity and scope**

### PHASE 3: TASK GROUPING

Organize ALL tasks into 4 groups based on complexity and dependencies:

| Group | Name | Criteria | Example tasks |
|-------|------|----------|---------------|
| **GROUP 1** | Foundation & Security | Prerequisites for everything else. No dependencies from other groups. | Database setup, authentication, JWT, base entities, security config, file upload infrastructure |
| **GROUP 2** | Core User Features | Depends on Group 1. Core user-facing functionality. | Deposit management, diagnostic requests, inspection report filling, auction viewing, bidding |
| **GROUP 3** | Admin & Diagnostic Features | Depends on Group 1-2. Admin and diagnostician panels. | Template builder (constructor), report approval/rejection, auction creation, bulk operations, user management, system settings |
| **GROUP 4** | Polish & Testing | Depends on all previous groups. Final touches. | WebSocket real-time updates, rate limiting, caching, load testing, E2E testing, mobile responsiveness, localization |

### PHASE 4: EXECUTION PLAN

For GROUP 1, create a detailed step-by-step execution plan:

1. List all tasks in GROUP 1 in logical order
2. For each task, specify:
   - What files need to be created/modified
   - Key implementation details
   - Acceptance criteria
   - Estimated complexity (Small/Medium/Large)

### OUTPUT FORMAT

Provide your response in the following structure:

```markdown
## ANALYSIS

### Summary of `script_res.txt`
(2-3 paragraphs summarizing the key scenarios and business logic)

### Summary of `tasks_res.txt`
(2-3 paragraphs summarizing the technical tasks)

### Gaps Identified
(List any missing tasks needed to fulfill the scenarios)

### Inconsistencies
(List any contradictions between scenarios and tasks)

### Improvement Suggestions
(Your recommendations for architecture, security, performance, UX)

## TASK GROUPING

### GROUP 1: Foundation & Security
(List tasks with brief justification)

### GROUP 2: Core User Features
(List tasks with brief justification)

### GROUP 3: Admin & Diagnostic Features
(List tasks with brief justification)

### GROUP 4: Polish & Testing
(List tasks with brief justification)

## EXECUTION PLAN FOR GROUP 1

### Task 1.1: [Task name]
- **Files**: ...
- **Implementation details**: ...
- **Acceptance criteria**: ...
- **Complexity**: Small/Medium/Large

### Task 1.2: ...
...

## NEXT STEPS

Once GROUP 1 is complete, I will report back and we will move to GROUP 2.