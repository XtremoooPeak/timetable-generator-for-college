// Central mock data for all modules

export const DEPARTMENTS = ['CSE', 'IT', 'ECE', 'ME', 'CE'];
export const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
export const SLOTS = ['8:00-9:00', '9:00-10:00', '10:00-11:00', '11:00-12:00', '12:00-1:00', '2:00-3:00', '3:00-4:00', '4:00-5:00'];

export const ROOMS = [
  { id: 'R101', name: 'Room 101', type: 'Lecture', capacity: 60, block: 'A' },
  { id: 'R102', name: 'Room 102', type: 'Lecture', capacity: 60, block: 'A' },
  { id: 'R201', name: 'Room 201', type: 'Lecture', capacity: 80, block: 'B' },
  { id: 'R202', name: 'Room 202', type: 'Lecture', capacity: 80, block: 'B' },
  { id: 'LAB1', name: 'CS Lab 1', type: 'Lab', capacity: 40, block: 'C' },
  { id: 'LAB2', name: 'CS Lab 2', type: 'Lab', capacity: 40, block: 'C' },
  { id: 'LAB3', name: 'IT Lab', type: 'Lab', capacity: 40, block: 'C' },
  { id: 'SEM1', name: 'Seminar Hall', type: 'Seminar', capacity: 120, block: 'D' },
];

export const FACULTY = [
  { id: 'F01', name: 'Dr. Priya Sharma', dept: 'CSE', designation: 'Associate Professor', courses: ['CS301'] },
  { id: 'F02', name: 'Dr. Amit Gupta', dept: 'CSE', designation: 'Professor', courses: ['CS201'] },
  { id: 'F03', name: 'Dr. Neha Joshi', dept: 'IT', designation: 'Assistant Professor', courses: ['IT301', 'IT201'] },
  { id: 'F04', name: 'Prof. Rajesh Kumar', dept: 'CSE', designation: 'Assistant Professor', courses: ['CS302'] },
  { id: 'F05', name: 'Dr. Sunita Rao', dept: 'ECE', designation: 'Associate Professor', courses: ['EC301', 'EC201'] },
  { id: 'F06', name: 'Dr. Vikram Singh', dept: 'ME', designation: 'Professor', courses: ['ME301', 'ME201'] },
];

export const COURSES = [
  { id: 'CS201', name: 'Data Structures', credits: 4, dept: 'CSE', type: 'Theory', semester: 3, sections: ['A', 'B'] },
  { id: 'CS301', name: 'Algorithms', credits: 4, dept: 'CSE', type: 'Theory', semester: 5, sections: ['A', 'B'] },
  { id: 'CS302', name: 'Database Systems', credits: 4, dept: 'CSE', type: 'Theory', semester: 5, sections: ['A', 'B'] },
  { id: 'CS401', name: 'Machine Learning', credits: 3, dept: 'CSE', type: 'Theory', semester: 7, sections: ['A'] },
  { id: 'CS402', name: 'Computer Networks', credits: 4, dept: 'CSE', type: 'Theory', semester: 7, sections: ['A', 'B'] },
  { id: 'CS501', name: 'AI & Deep Learning', credits: 3, dept: 'CSE', type: 'Theory', semester: 7, sections: ['A'] },
  { id: 'CS-LAB1', name: 'DS Lab', credits: 2, dept: 'CSE', type: 'Lab', semester: 3, sections: ['A', 'B'] },
  { id: 'CS-LAB2', name: 'DB Lab', credits: 2, dept: 'CSE', type: 'Lab', semester: 5, sections: ['A', 'B'] },
  { id: 'IT301', name: 'Web Technologies', credits: 4, dept: 'IT', type: 'Theory', semester: 5, sections: ['A'] },
  { id: 'IT201', name: 'OS Concepts', credits: 4, dept: 'IT', type: 'Theory', semester: 3, sections: ['A'] },
];

// A sample generated timetable for Semester 5, Section A, CSE
export const SAMPLE_TIMETABLE = {
  semester: 5, dept: 'CSE', section: 'A',
  entries: [
    { day: 'Monday', slot: '8:00-9:00', course: 'CS301', faculty: 'F01', room: 'R101', color: '#1a4a8a' },
    { day: 'Monday', slot: '9:00-10:00', course: 'CS302', faculty: 'F04', room: 'R101', color: '#c0392b' },
    { day: 'Monday', slot: '10:00-11:00', course: 'CS401', faculty: 'F01', room: 'R201', color: '#1a7a46' },
    { day: 'Monday', slot: '2:00-3:00', course: 'CS-LAB2', faculty: 'F04', room: 'LAB1', color: '#b7620a' },
    { day: 'Monday', slot: '3:00-4:00', course: 'CS-LAB2', faculty: 'F04', room: 'LAB1', color: '#b7620a' },
    { day: 'Tuesday', slot: '8:00-9:00', course: 'CS402', faculty: 'F04', room: 'R202', color: '#5a2d82' },
    { day: 'Tuesday', slot: '9:00-10:00', course: 'CS501', faculty: 'F02', room: 'R201', color: '#0d6e7a' },
    { day: 'Tuesday', slot: '10:00-11:00', course: 'CS301', faculty: 'F01', room: 'R101', color: '#1a4a8a' },
    { day: 'Wednesday', slot: '8:00-9:00', course: 'CS302', faculty: 'F04', room: 'R101', color: '#c0392b' },
    { day: 'Wednesday', slot: '11:00-12:00', course: 'CS402', faculty: 'F04', room: 'R202', color: '#5a2d82' },
    { day: 'Wednesday', slot: '2:00-3:00', course: 'CS-LAB2', faculty: 'F04', room: 'LAB2', color: '#b7620a' },
    { day: 'Wednesday', slot: '3:00-4:00', course: 'CS-LAB2', faculty: 'F04', room: 'LAB2', color: '#b7620a' },
    { day: 'Thursday', slot: '8:00-9:00', course: 'CS501', faculty: 'F02', room: 'R201', color: '#0d6e7a' },
    { day: 'Thursday', slot: '9:00-10:00', course: 'CS401', faculty: 'F01', room: 'R201', color: '#1a7a46' },
    { day: 'Thursday', slot: '10:00-11:00', course: 'CS302', faculty: 'F04', room: 'R101', color: '#c0392b' },
    { day: 'Friday', slot: '8:00-9:00', course: 'CS301', faculty: 'F01', room: 'R101', color: '#1a4a8a' },
    { day: 'Friday', slot: '9:00-10:00', course: 'CS402', faculty: 'F04', room: 'R202', color: '#5a2d82' },
    { day: 'Friday', slot: '11:00-12:00', course: 'CS501', faculty: 'F02', room: 'R201', color: '#0d6e7a' },
    { day: 'Saturday', slot: '8:00-9:00', course: 'CS302', faculty: 'F04', room: 'R101', color: '#c0392b' },
    { day: 'Saturday', slot: '9:00-10:00', course: 'CS301', faculty: 'F01', room: 'R101', color: '#1a4a8a' },
  ]
};

export const CONFLICT_RULES = [
  'No faculty can be assigned to two classes at the same time slot',
  'No room can host two different classes at the same time slot',
  'Lab sessions must be scheduled in 2-hour contiguous blocks',
  'No class can be scheduled before 8:00 AM or after 5:00 PM',
  'Maximum 5 hours of teaching per faculty per day',
];