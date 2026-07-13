import React, { useState, useEffect } from 'react';
import Navbar from '../components/Navbar';
import { useAuth, ROLES } from '../context/AuthContext';
import { getTimetable, saveTimetable, validateTimetable, getSuggestions, importCollection, clearTimetable, autoFixTimetable, getCollection } from '../api';
import { DAYS, SLOTS, FACULTY as INIT_FACULTY, ROOMS as INIT_ROOMS, DEPARTMENTS as INIT_DEPTS, COURSES } from '../utils/data';
import {
  Box,
  Card,
  CardContent,
  Typography,
  Grid,
  Button,
  Select,
  MenuItem,
  FormControl,
  InputLabel,
  IconButton,
  Chip,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  CircularProgress,
  Divider,
  Paper
} from '@mui/material';
import {
  Undo as UndoIcon,
  Redo as RedoIcon,
  CloudUpload as CloudUploadIcon,
  CloudDownload as CloudDownloadIcon,
  Lightbulb as LightbulbIcon,
  WarningAmber as WarningAmberIcon,
  Save as SaveIcon,
  FileOpen as FileOpenIcon,
  DeleteSweep as DeleteSweepIcon,
  AutoAwesome as AutoAwesomeIcon
} from '@mui/icons-material';


const sortName = name => name.replace(/^(dr\.?|prof\.?)\s+/i, '').trim();

export default function TimetableView() {
  const { user } = useAuth();
  const isAdmin = user?.role === ROLES.ADMIN;

  // Timetable State & History
  const [entries, setEntries] = useState([]);
  const [history, setHistory] = useState([]);
  const [historyIndex, setHistoryIndex] = useState(-1);
  const [savingStatus, setSavingStatus] = useState('Saved'); // 'Saved', 'Saving', 'Error'

  // View Filtering State
  const [viewType, setViewType] = useState('student'); // 'student', 'faculty', 'room', 'dept'
  const [selectedFaculty, setSelectedFaculty] = useState('F01');
  const [selectedRoom, setSelectedRoom] = useState('R101');
  const [selectedDept, setSelectedDept] = useState('CSE');
  const [selectedSemester, setSelectedSemester] = useState('5');
  const [selectedSection, setSelectedSection] = useState('A');
  const [facultyList, setFacultyList] = useState(INIT_FACULTY);
  const [roomList, setRoomList] = useState(INIT_ROOMS);
  const [deptList, setDeptList] = useState(INIT_DEPTS);

  // Conflict / Score State
  const [validation, setValidation] = useState({ hardCount: 0, softCount: 0, overallScore: 100, conflicts: [], softViolations: [] });

  // UI Selection State
  const [selectedCell, setSelectedCell] = useState(null); // { day, slot, entry }
  const [suggestions, setSuggestions] = useState([]);
  const [suggestLoading, setSuggestLoading] = useState(false);

  // Import Dialog State
  const [importOpen, setImportOpen] = useState(false);
  const [importType, setImportType] = useState('courses');
  const [importCsv, setImportCsv] = useState('');

  // Clear Dialog State
  const [clearDialogOpen, setClearDialogOpen] = useState(false);
  const [clearSemester, setClearSemester] = useState('All');
  const [fixing, setFixing] = useState(false);

  const handleClearTimetable = async () => {
    if (!window.confirm(`Are you sure you want to clear the timetable for ${clearSemester === 'All' ? 'all semesters' : `Semester ${clearSemester}`}?`)) {
      return;
    }
    try {
      setSavingStatus('Saving');
      const res = await clearTimetable(clearSemester, selectedDept);
      alert(`Successfully cleared ${res.cleared} entries!`);
      setClearDialogOpen(false);
      loadTimetableData();
    } catch (err) {
      alert(`Clear failed: ${err.message}`);
      setSavingStatus('Error');
    }
  };

  const handleAutoFix = async () => {
    setFixing(true);
    setSavingStatus('Saving');
    try {
      const res = await autoFixTimetable(entries, selectedDept, selectedSemester);
      if (res.timetable) {
        setEntries(res.timetable);
        runValidation(res.timetable);
        // Update history
        const nextHistory = history.slice(0, historyIndex + 1);
        setHistory([...nextHistory, res.timetable]);
        setHistoryIndex(nextHistory.length);
        setSavingStatus('Saved');
        alert(`AI optimization complete! Resolved violations. Overall optimization score is now ${res.overallScore}%.`);
      } else {
        alert("AI optimization failed to return a valid schedule.");
        setSavingStatus('Error');
      }
    } catch (err) {
      alert(`AI optimization failed: ${err.message}`);
      setSavingStatus('Error');
    } finally {
      setFixing(false);
    }
  };

  // Load Initial Timetable & Faculty / Rooms / Depts
  useEffect(() => {
    loadTimetableData();
    // Faculty — only teachers with courses assigned
    getCollection('faculty', INIT_FACULTY).then(data => {
      const teachers = data
        .filter(f => f.courses && f.courses.length > 0)
        .sort((a, b) => sortName(a.name).localeCompare(sortName(b.name)));
      setFacultyList(teachers);
      if (teachers.length > 0) setSelectedFaculty(teachers[0].id);
      // Derive unique departments from faculty list
      const depts = [...new Set(data.map(f => f.dept).filter(Boolean))].sort();
      if (depts.length > 0) {
        setDeptList(depts);
        setSelectedDept(depts[0]);
      }
    });
    // Rooms
    getCollection('rooms', INIT_ROOMS).then(data => {
      setRoomList(data);
      if (data.length > 0) setSelectedRoom(data[0].id);
    });
  }, []);

  const loadTimetableData = async () => {
    try {
      const data = await getTimetable();
      setEntries(data);
      runValidation(data);
      // Init history
      setHistory([data]);
      setHistoryIndex(0);
    } catch (err) {
      console.error("Failed to load timetable", err);
    }
  };

  // Run Backend Validation
  const runValidation = async (currentEntries) => {
    try {
      const res = await validateTimetable(currentEntries);
      setValidation({
        hardCount: res.hardViolations,
        softCount: res.softViolations,
        overallScore: res.overallScore,
        conflicts: res.conflicts || [],
        softViolations: res.softConstraintViolations || []
      });
    } catch (err) {
      console.error("Validation error", err);
    }
  };

  // Trigger Debounced Auto-Save
  const triggerAutoSave = async (updatedEntries) => {
    setSavingStatus('Saving...');
    try {
      await saveTimetable(updatedEntries);
      setSavingStatus('Saved');
    } catch (err) {
      console.error(err);
      setSavingStatus('Local Save');
    }
  };

  // History Operations (Undo / Redo)
  const updateEntriesState = (newEntries) => {
    const updatedHistory = history.slice(0, historyIndex + 1);
    updatedHistory.push(newEntries);
    setHistory(updatedHistory);
    setHistoryIndex(updatedHistory.length - 1);
    setEntries(newEntries);
    runValidation(newEntries);
    triggerAutoSave(newEntries);
  };

  const handleUndo = () => {
    if (historyIndex > 0) {
      const idx = historyIndex - 1;
      setHistoryIndex(idx);
      setEntries(history[idx]);
      runValidation(history[idx]);
      triggerAutoSave(history[idx]);
    }
  };

  const handleRedo = () => {
    if (historyIndex < history.length - 1) {
      const idx = historyIndex + 1;
      setHistoryIndex(idx);
      setEntries(history[idx]);
      runValidation(history[idx]);
      triggerAutoSave(history[idx]);
    }
  };

  // Filter entries to display depending on the active view
  const getFilteredEntries = () => {
    return entries.filter(e => {
      if (viewType === 'student') {
        return e.semester === parseInt(selectedSemester) && e.section === selectedSection && e.dept === selectedDept;
      }
      if (viewType === 'faculty') {
        return e.faculty === selectedFaculty;
      }
      if (viewType === 'room') {
        return e.room === selectedRoom;
      }
      if (viewType === 'dept') {
        return e.dept === selectedDept;
      }
      return true;
    });
  };

  const filteredEntries = getFilteredEntries();

  const filteredConflicts = validation.conflicts.filter(c => {
    const affectsSem = c.affectedCourses?.some(courseId => {
      const course = COURSES.find(item => item.id === courseId);
      return course && String(course.semester) === String(selectedSemester);
    });
    const hasClassInCell = filteredEntries.some(e => e.day === c.day && e.slot === c.slot);
    return affectsSem || hasClassInCell;
  });

  const getUnassignedCourses = () => {
    if (viewType !== 'student') return [];
    const semCourses = COURSES.filter(c =>
      c.dept === selectedDept &&
      String(c.semester) === String(selectedSemester) &&
      (c.sections ? c.sections.includes(selectedSection) : true)
    );
    return semCourses.map(course => {
      const scheduledCount = filteredEntries.filter(e => e.course === course.id).length;
      const requiredCount = course.credits || 3;
      const remaining = requiredCount - scheduledCount;
      return {
        ...course,
        scheduledCount,
        requiredCount,
        remaining
      };
    }).filter(c => c.remaining > 0);
  };
  const unassignedCourses = getUnassignedCourses();

  const getCellEntry = (day, slot) => {
    return filteredEntries.find(e => e.day === day && e.slot === slot);
  };

  // HTML5 Drag and Drop Event Handlers
  const handleDragStart = (e, entry) => {
    if (!isAdmin) return;
    e.dataTransfer.setData("text/plain", JSON.stringify(entry));
  };

  const handleDrop = (e, targetDay, targetSlot) => {
    if (!isAdmin) return;
    e.preventDefault();
    try {
      const draggedEntry = JSON.parse(e.dataTransfer.getData("text/plain"));

      // Update entry day and slot
      const updatedEntries = entries.map(item => {
        if (item.course === draggedEntry.course && item.day === draggedEntry.day && item.slot === draggedEntry.slot && item.section === draggedEntry.section) {
          return { ...item, day: targetDay, slot: targetSlot };
        }
        // If there was another item in the target slot, we do a swap to avoid duplicate conflict creation
        if (item.day === targetDay && item.slot === targetSlot && item.section === draggedEntry.section && item.semester === draggedEntry.semester) {
          return { ...item, day: draggedEntry.day, slot: draggedEntry.slot };
        }
        return item;
      });

      updateEntriesState(updatedEntries);
    } catch (err) {
      console.error("Drop failed", err);
    }
  };

  const handleDragOver = (e) => {
    e.preventDefault();
  };

  // Handle Cell Click (retrieve AI suggestions)
  const handleCellClick = async (day, slot, entry) => {
    setSelectedCell({ day, slot, entry });
    if (!entry) {
      setSuggestions([]);
      return;
    }
    setSuggestLoading(true);
    try {
      const res = await getSuggestions({
        entry: entry,
        currentTimetable: entries
      });
      setSuggestions(res.suggestions || []);
    } catch (err) {
      console.error(err);
    } finally {
      setSuggestLoading(false);
    }
  };

  // Apply suggestion
  const applySuggestion = (sug) => {
    if (!selectedCell || !selectedCell.entry) return;
    const targetEntry = selectedCell.entry;

    const updated = entries.map(item => {
      if (item.course === targetEntry.course && item.day === targetEntry.day && item.slot === targetEntry.slot && item.section === targetEntry.section) {
        return { ...item, day: sug.newDay, slot: sug.newSlot, room: sug.newRoom };
      }
      return item;
    });

    updateEntriesState(updated);
    setSelectedCell(null);
  };

  // Export to CSV/Excel
  const handleExportCsv = () => {
    let csvContent = "data:text/csv;charset=utf-8,Day,Slot,Course ID,Faculty,Room,Section,Semester,Dept\n";
    entries.forEach(e => {
      csvContent += `${e.day},${e.slot},${e.course},${e.faculty},${e.room},${e.section},${e.semester},${e.dept}\n`;
    });
    const encodedUri = encodeURI(csvContent);
    const link = document.createElement("a");
    link.setAttribute("href", encodedUri);
    link.setAttribute("download", "university_timetable.csv");
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  // Import CSV handler
  const handleImportSubmit = async () => {
    if (!importCsv.trim()) return;
    try {
      const res = await importCollection(importType, importCsv);
      alert(`Import Successful! Imported ${res.importedCount} items.`);
      setImportOpen(false);
      setImportCsv('');
      loadTimetableData(); // reload
    } catch (err) {
      alert(`Import Failed: ${err.message}`);
    }
  };

  return (
    <>
      <Navbar title="Active Timetable Dashboard" />
      <Box className="page" sx={{ p: 3 }}>

        {/* Undo/Redo & Save status & Import/Export Bar */}
        <Paper
          sx={{
            p: 2,
            mb: 3,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            background: 'rgba(255, 255, 255, 0.04)',
            backdropFilter: 'blur(10px)',
            border: '1px solid rgba(255,255,255,0.05)',
            boxShadow: '0 8px 32px 0 rgba(0, 0, 0, 0.15)'
          }}
        >
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <IconButton onClick={handleUndo} disabled={historyIndex <= 0} color="primary">
              <UndoIcon />
            </IconButton>
            <IconButton onClick={handleRedo} disabled={historyIndex >= history.length - 1} color="primary">
              <RedoIcon />
            </IconButton>
            <Chip
              icon={<SaveIcon />}
              label={savingStatus}
              color={savingStatus === 'Saved' ? 'success' : 'primary'}
              variant="outlined"
              size="small"
              sx={{ ml: 2, fontWeight: 700 }}
            />
          </Box>

          <Box sx={{ display: 'flex', gap: 1.5 }}>
            {isAdmin && (
              <>

                <Button variant="outlined" color="error" startIcon={<DeleteSweepIcon />} onClick={() => setClearDialogOpen(true)}>
                  Clear / Reset
                </Button>
              </>
            )}
            <Button variant="outlined" startIcon={<CloudUploadIcon />} onClick={() => setImportOpen(true)}>
              Import CSV
            </Button>
            <Button variant="outlined" startIcon={<CloudDownloadIcon />} onClick={handleExportCsv}>
              Export Excel / CSV
            </Button>
            <Button variant="contained" onClick={() => window.print()}>
              Export PDF / Print
            </Button>
          </Box>
        </Paper>

        <Box sx={{ display: 'flex', gap: 3, alignItems: 'flex-start', flexWrap: { xs: 'wrap', md: 'nowrap' } }}>
          {/* Main Timetable View */}
          <Box sx={{ flex: 1, minWidth: 0, width: '100%' }}>

            {/* View filtering selectors */}
            <Card sx={{ mb: 3 }}>
              <CardContent sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center' }}>
                <FormControl variant="outlined" size="small" sx={{ minWidth: 150 }}>
                  <InputLabel>View Perspective</InputLabel>
                  <Select value={viewType} onChange={e => setViewType(e.target.value)} label="View Perspective">
                    <MenuItem value="student">Student Section</MenuItem>
                    <MenuItem value="faculty">Faculty Member</MenuItem>
                    <MenuItem value="room">Room / Lab</MenuItem>
                    <MenuItem value="dept">Entire Department</MenuItem>
                  </Select>
                </FormControl>

                {/* Conditional Sub-selectors */}
                {viewType === 'student' && (
                  <>
                    <FormControl variant="outlined" size="small" sx={{ minWidth: 100 }}>
                      <Select value={selectedDept} onChange={e => setSelectedDept(e.target.value)}>
                        {deptList.map(d => <MenuItem key={d} value={d}>{d}</MenuItem>)}
                      </Select>
                    </FormControl>
                    <FormControl variant="outlined" size="small" sx={{ minWidth: 100 }}>
                      <Select value={selectedSemester} onChange={e => setSelectedSemester(e.target.value)}>
                        {[1, 2, 3, 4, 5, 6, 7, 8].map(s => <MenuItem key={s} value={String(s)}>Sem {s}</MenuItem>)}
                      </Select>
                    </FormControl>
                    <FormControl variant="outlined" size="small" sx={{ minWidth: 100 }}>
                      <Select value={selectedSection} onChange={e => setSelectedSection(e.target.value)}>
                        {['A', 'B', 'C'].map(s => <MenuItem key={s} value={s}>Sec {s}</MenuItem>)}
                      </Select>
                    </FormControl>
                  </>
                )}

                {viewType === 'faculty' && (
                  <FormControl variant="outlined" size="small" sx={{ minWidth: 200 }}>
                    <Select value={selectedFaculty} onChange={e => setSelectedFaculty(e.target.value)}>
                      {facultyList.map(f => <MenuItem key={f.id} value={f.id}>{f.name}</MenuItem>)}
                    </Select>
                  </FormControl>
                )}

                {viewType === 'room' && (
                  <FormControl variant="outlined" size="small" sx={{ minWidth: 150 }}>
                    <Select value={selectedRoom} onChange={e => setSelectedRoom(e.target.value)}>
                      {roomList.map(r => <MenuItem key={r.id} value={r.id}>{r.name}</MenuItem>)}
                    </Select>
                  </FormControl>
                )}

                {viewType === 'dept' && (
                  <FormControl variant="outlined" size="small" sx={{ minWidth: 120 }}>
                    <Select value={selectedDept} onChange={e => setSelectedDept(e.target.value)}>
                      {deptList.map(d => <MenuItem key={d} value={d}>{d}</MenuItem>)}
                    </Select>
                  </FormControl>
                )}
              </CardContent>
            </Card>

            {/* Grid Schedule */}
            <Card>
              <Box sx={{ overflowX: 'auto' }}>
                <table className="tt-grid">
                  <thead>
                    <tr>
                      <th>Time / Day</th>
                      {DAYS.map(d => <th key={d}>{d.slice(0, 3)}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {SLOTS.map(slot => (
                      <tr key={slot}>
                        {/* Time cell */}
                        <td style={{ fontWeight: 700, fontSize: 11, background: 'rgba(255,255,255,0.03)' }}>
                          {slot}
                        </td>

                        {/* Day cells */}
                        {DAYS.map(day => {
                          const entry = getCellEntry(day, slot);
                          const isClashed = validation.conflicts.some(c => c.day === day && c.slot === slot);

                          return (
                            <td
                              key={day}
                              onDragOver={handleDragOver}
                              onDrop={(e) => handleDrop(e, day, slot)}
                              onClick={() => handleCellClick(day, slot, entry)}
                              style={{
                                border: isClashed ? '2px solid #ef5350' : '1px solid rgba(255,255,255,0.05)',
                                cursor: 'pointer',
                                background: selectedCell?.day === day && selectedCell?.slot === slot ? 'rgba(26, 122, 239, 0.1)' : 'transparent',
                                transition: 'background 0.2s',
                                verticalAlign: 'middle',
                                padding: 4
                              }}
                            >
                              {entry ? (
                                <div
                                  draggable={isAdmin}
                                  onDragStart={(e) => handleDragStart(e, entry)}
                                  className="tt-cell"
                                  style={{
                                    background: entry.color ? `${entry.color}22` : 'rgba(26, 122, 239, 0.15)',
                                    borderLeft: `4px solid ${entry.color || '#1a7aef'}`,
                                    padding: '6px 8px',
                                    borderRadius: 6,
                                    margin: 2
                                  }}
                                >
                                  <Typography variant="subtitle2" sx={{ fontWeight: 800, fontSize: 11.5, color: entry.color || '#1a7aef', m: 0 }}>
                                    {entry.course}
                                  </Typography>
                                  <Typography variant="caption" sx={{ fontSize: 9.5, color: 'text.secondary', display: 'block' }}>
                                    {entry.room} · Sec {entry.section}
                                  </Typography>
                                </div>
                              ) : (
                                <Box sx={{ minHeight: 40 }} />
                              )}
                            </td>
                          );
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Box>
            </Card>
          </Box>

          {/* Right Panels: Score Card & Conflict Resolution & Suggestions */}
          <Box sx={{ width: { xs: '100%', md: 320 }, flexShrink: 0, display: 'flex', flexDirection: 'column' }}>

            {/* Score Card */}
            <Card sx={{ mb: 3 }}>
              <CardContent sx={{ textAlign: 'center', p: 3 }}>
                <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700, textTransform: 'uppercase' }}>
                  Optimization Score
                </Typography>
                <Typography variant="h3" sx={{ fontWeight: 800, my: 1, color: 'primary.main' }}>
                  {validation.overallScore}%
                </Typography>
                <Box sx={{ display: 'flex', justifyContent: 'space-around', mt: 2 }}>
                  <Box>
                    <Typography variant="caption" color="text.secondary" display="block">Hard Violations</Typography>
                    <Typography variant="subtitle2" sx={{ fontWeight: 700, color: validation.hardCount > 0 ? 'error.main' : 'success.main' }}>
                      {validation.hardCount}
                    </Typography>
                  </Box>
                  <Box>
                    <Typography variant="caption" color="text.secondary" display="block">Soft Violations</Typography>
                    <Typography variant="subtitle2" sx={{ fontWeight: 700, color: 'warning.main' }}>
                      {validation.softCount}
                    </Typography>
                  </Box>
                </Box>
              </CardContent>
            </Card>

            {/* Suggestions Panel */}
            <Card sx={{ mb: 3 }}>
              <CardContent>
                <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 2, display: 'flex', alignItems: 'center', gap: 1 }}>
                  <LightbulbIcon color="primary" /> AI Smart Suggestions
                </Typography>

                {selectedCell?.entry ? (
                  <>
                    <Typography variant="caption" color="text.secondary" sx={{ mb: 1, display: 'block' }}>
                      Alternate slots for {selectedCell.entry.course}:
                    </Typography>
                    {suggestLoading ? (
                      <Box sx={{ textAlign: 'center', py: 2 }}><CircularProgress size={24} /></Box>
                    ) : suggestions.length === 0 ? (
                      <Typography variant="caption" color="text.secondary">No alternate recommendations found.</Typography>
                    ) : (
                      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                        {suggestions.map((sug, idx) => (
                          <Paper
                            key={idx}
                            variant="outlined"
                            sx={{
                              p: 1.5,
                              cursor: 'pointer',
                              borderColor: 'rgba(255,255,255,0.08)',
                              '&:hover': { background: 'rgba(26, 122, 239, 0.08)' }
                            }}
                            onClick={() => applySuggestion(sug)}
                          >
                            <Typography variant="subtitle2" sx={{ fontSize: 12, fontWeight: 700 }}>
                              {sug.newDay} · {sug.newSlot}
                            </Typography>
                            <Typography variant="caption" color="text.secondary" display="block">
                              Room: {sug.newRoom} | {sug.reason}
                            </Typography>
                          </Paper>
                        ))}
                      </Box>
                    )}
                  </>
                ) : (
                  <Typography variant="caption" color="text.secondary">
                    Select a scheduled class cell on the grid to load AI placement recommendations.
                  </Typography>
                )}
              </CardContent>
            </Card>

            {/* Unassigned Courses Card */}
            {viewType === 'student' && unassignedCourses.length > 0 && (
              <Card sx={{ mb: 3 }}>
                <CardContent>
                  <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 2, display: 'flex', alignItems: 'center', gap: 1 }}>
                    📌 Unassigned Classes ({unassignedCourses.length})
                  </Typography>
                  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                    {unassignedCourses.map(c => (
                      <Box
                        key={c.id}
                        sx={{
                          p: 1.5,
                          borderRadius: 2,
                          bgcolor: 'rgba(26, 122, 239, 0.04)',
                          border: '1px solid rgba(26, 122, 239, 0.08)',
                          borderLeft: '4px solid #1a7aef'
                        }}
                      >
                        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 0.5 }}>
                          <Typography variant="subtitle2" sx={{ fontSize: 12, fontWeight: 700, color: 'text.primary' }}>
                            {c.id} · {c.name}
                          </Typography>
                          <Chip label={`${c.remaining} left`} size="small" color="warning" variant="outlined" sx={{ height: 18, fontSize: 10, fontWeight: 700 }} />
                        </Box>
                        <Typography variant="caption" color="text.secondary" display="block">
                          Scheduled: {c.scheduledCount} / {c.requiredCount} lectures
                        </Typography>
                      </Box>
                    ))}
                  </Box>
                </CardContent>
              </Card>
            )}

            {/* Active Conflicts List */}
            {filteredConflicts.length > 0 && (
              <Card sx={{ borderColor: 'error.main' }}>
                <CardContent>
                  <Typography variant="subtitle2" sx={{ fontWeight: 700, color: 'error.main', mb: 2, display: 'flex', alignItems: 'center', gap: 1 }}>
                    <WarningAmberIcon /> Constraint Conflicts ({filteredConflicts.length})
                  </Typography>
                  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                    {filteredConflicts.map((c, i) => (
                      <Box
                        key={i}
                        sx={{
                          p: 1.5,
                          borderRadius: 2,
                          bgcolor: 'rgba(239, 83, 80, 0.08)',
                          borderLeft: '4px solid #ef5350'
                        }}
                      >
                        <Typography variant="subtitle2" sx={{ fontSize: 12, fontWeight: 700, color: 'text.primary' }}>
                          {c.type}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" display="block">
                          {c.desc}
                        </Typography>
                      </Box>
                    ))}
                  </Box>
                </CardContent>
              </Card>
            )}
          </Box>
        </Box>
      </Box>

      {/* CSV Import Modal */}
      <Dialog open={importOpen} onClose={() => setImportOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle sx={{ fontWeight: 700 }}>Import Data via CSV</DialogTitle>
        <DialogContent>
          <FormControl fullWidth variant="outlined" size="small" sx={{ my: 2 }}>
            <InputLabel>Registry Target</InputLabel>
            <Select value={importType} onChange={e => setImportType(e.target.value)} label="Registry Target">
              <MenuItem value="courses">Courses Registry</MenuItem>
              <MenuItem value="faculty">Faculty Registry</MenuItem>
              <MenuItem value="rooms">Rooms Registry</MenuItem>
            </Select>
          </FormControl>
          <TextField
            multiline
            rows={8}
            fullWidth
            variant="outlined"
            placeholder="id,name,credits,dept,type,semester,sections
CS501,Advanced AI,4,CSE,Theory,5,A;B"
            value={importCsv}
            onChange={e => setImportCsv(e.target.value)}
            helperText="Provide CSV strings. Separate sections or arrays using semicolon (;)"
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setImportOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={handleImportSubmit}>Submit Import</Button>
        </DialogActions>
      </Dialog>

      {/* Clear Timetable Modal */}
      <Dialog open={clearDialogOpen} onClose={() => setClearDialogOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle sx={{ fontWeight: 700 }}>Clear Timetable</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
            Choose a specific semester to clear its timetable, or clear all semesters.
          </Typography>
          <FormControl fullWidth variant="outlined" size="small">
            <InputLabel id="clear-sem-label">Target Semester</InputLabel>
            <Select
              labelId="clear-sem-label"
              value={clearSemester}
              onChange={e => setClearSemester(e.target.value)}
              label="Target Semester"
            >
              <MenuItem value="All">All Semesters</MenuItem>
              {[1, 2, 3, 4, 5, 6, 7, 8].map(s => (
                <MenuItem key={s} value={String(s)}>Semester {s}</MenuItem>
              ))}
            </Select>
          </FormControl>
        </DialogContent>
        <DialogActions sx={{ p: 2, pt: 0 }}>
          <Button onClick={() => setClearDialogOpen(false)}>Cancel</Button>
          <Button variant="contained" color="error" onClick={handleClearTimetable}>
            Clear Selected
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
