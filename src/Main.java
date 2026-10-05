import java.io.BufferedOutputStream; // output ko fast likhne ke liye buffer
import java.io.FileDescriptor; // stdout ka descriptor lene ke liye
import java.io.FileOutputStream; // raw bytes seedha stdout par likhne ke liye
import java.io.IOException; // file padhne ke error ke liye
import java.io.OutputStream; // output stream ka type
import java.nio.charset.StandardCharsets; // UTF-8 decode karne ke liye
import java.nio.file.Files; // file ke bytes padhne ke liye
import java.nio.file.Paths; // path banane ke liye
import java.util.Arrays; // fill aur equals ke liye

public class Main { // main class (default package, file ka naam Main.java)

    static int exitCode = 0; // program ka exit code (0 = theek, 2 = file error)

    // ---------------- ek file ki lines ka data ----------------
    static class Lines { // ek file ki saari lines ko store karta hai
        byte[] data; // file ke raw bytes
        int n; // lines ki sankhya
        int[] start; // har line ka start index (data me)
        int[] end; // har line ka end index (newline ke bina, exclusive)
    }

    // raw bytes ko lines me todta hai (\n par), \r line ka hissa rehta hai
    static Lines split(byte[] data) { // file ke bytes input
        Lines L = new Lines(); // naya Lines object
        L.data = data; // bytes save karo
        int count = 0; // newline bytes ginne ke liye
        for (byte x : data) { // har byte par
            if (x == '\n') { // agar newline byte hai
                count++; // line ginti badhao
            }
        }
        if (data.length > 0 && data[data.length - 1] != '\n') { // agar last byte newline nahi hai
            count++; // to aakhri line bhi ginni hai (bina newline wali)
        }
        L.n = count; // total lines
        L.start = new int[count]; // start array banao
        L.end = new int[count]; // end array banao
        int pos = 0; // abhi kahan se padh rahe hain
        for (int i = 0; i < count; i++) { // har line ke liye
            int j = pos; // line ka end dhundhne ke liye pointer
            while (j < data.length && data[j] != '\n') { // jab tak newline na mile
                j++; // aage badho
            }
            L.start[i] = pos; // line ka start
            L.end[i] = j; // line ka end (newline ke bina)
            pos = j + 1; // agli line newline ke baad shuru hogi
        }
        return L; // Lines return
    }

    // bytes ka hash nikalta hai (lines ko compare karne ke liye)
    static int hashBytes(byte[] d, int s, int e) { // data, start, end
        int h = 1; // shuruaati hash
        for (int i = s; i < e; i++) { // har byte par
            h = 31 * h + d[i]; // hash update
        }
        h ^= (h >>> 16); // bits mix karo
        h *= 0x45d9f3b; // aur mix karo
        h ^= (h >>> 16); // aakhri mixing
        return h; // hash return
    }

    // har unique line ko ek integer id deta hai, taaki compare O(1) me ho (int == int)
    static void intern(Lines A, Lines B, int[] idA, int[] idB) { // dono files ki lines aur id arrays
        int total = A.n + B.n; // dono files ki total lines
        int size = 16; // hash table ka minimum size
        while (size < 2 * total) { // table ko lines se kam se kam double rakho
            size <<= 1; // power of two banate jao
        }
        int[] table = new int[size]; // table (0 = khali, warna global index + 1)
        int mask = size - 1; // index nikalne ka mask
        for (int g = 0; g < total; g++) { // har line (A ki phir B ki) par
            Lines L = (g < A.n) ? A : B; // ye line kis file ki hai
            int li = (g < A.n) ? g : g - A.n; // us file me line ka number
            int s = L.start[li]; // line ka start
            int e = L.end[li]; // line ka end
            int idx = hashBytes(L.data, s, e) & mask; // table me jagah
            int id = -1; // abhi id nahi mili
            while (table[idx] != 0) { // jab tak slot bhara hai
                int h = table[idx] - 1; // us slot wali line ka global index
                Lines M = (h < A.n) ? A : B; // wo line kis file ki hai
                int mi = (h < A.n) ? h : h - A.n; // us file me number
                if (Arrays.equals(M.data, M.start[mi], M.end[mi], L.data, s, e)) { // bytes bilkul same hain?
                    id = h; // haan, wahi id use karo
                    break; // dhundhna band
                }
                idx = (idx + 1) & mask; // agla slot dekho
            }
            if (id < 0) { // nayi unique line mili
                table[idx] = g + 1; // slot me save karo
                id = g; // iski id wahi global index
            }
            if (g < A.n) { // agar A ki line hai
                idA[g] = id; // A ke id array me
            } else { // warna B ki line hai
                idB[g - A.n] = id; // B ke id array me
            }
        }
    }

    // ---------------- MYERS DIFF (linear space, divide and conquer) ----------------
    // kisi bhi int sequence (line ids ya characters) par kaam karta hai
    static class Differ { // diff nikalne wala class
        int[] a; // purani sequence
        int[] b; // nayi sequence
        boolean[] delA; // delA[i] = true matlab a[i] delete hua
        boolean[] insB; // insB[j] = true matlab b[j] insert hua
        int[] v1; // forward V array
        int[] v2; // reverse V array
        int off; // negative k ke liye offset

        Differ(int[] a, int[] b) { // constructor
            this.a = a; // purani sequence save
            this.b = b; // nayi sequence save
            delA = new boolean[a.length]; // sab false se shuru
            insB = new boolean[b.length]; // sab false se shuru
            int maxD = (a.length + b.length + 1) / 2 + 2; // d ki upper limit
            off = maxD + 2; // offset
            v1 = new int[2 * maxD + 5]; // forward array ek baar banao
            v2 = new int[2 * maxD + 5]; // reverse array ek baar banao
        }

        void run() { // poori diff chalao
            solve(0, a.length, 0, b.length); // poori range par solve
        }

        // a[aLo..aHi) aur b[bLo..bHi) ka minimal diff, delA/insB me mark karta hai
        void solve(int aLo, int aHi, int bLo, int bHi) { // range input
            while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) { // shuru ke common elements
                aLo++; // a ka start aage
                bLo++; // b ka start aage
            }
            while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) { // ant ke common elements
                aHi--; // a ka end peeche
                bHi--; // b ka end peeche
            }
            if (aLo == aHi) { // a khatam, b me jo bacha wo sab insert
                for (int j = bLo; j < bHi; j++) { // b ke bache elements
                    insB[j] = true; // insert mark karo
                }
                return; // kaam khatam
            }
            if (bLo == bHi) { // b khatam, a me jo bacha wo sab delete
                for (int i = aLo; i < aHi; i++) { // a ke bache elements
                    delA[i] = true; // delete mark karo
                }
                return; // kaam khatam
            }
            int n = aHi - aLo; // a ki length
            int m = bHi - bLo; // b ki length
            int delta = n - m; // dono ki length ka farak
            boolean front = (delta & 1) != 0; // delta odd hai to overlap forward pass me milega
            int maxD = (n + m + 1) / 2; // is range ke liye d ki limit
            Arrays.fill(v1, off - maxD - 1, off + maxD + 2, -1); // forward array reset (-1 = khali)
            Arrays.fill(v2, off - maxD - 1, off + maxD + 2, -1); // reverse array reset
            v1[off + 1] = 0; // forward ki shuruaat
            v2[off + 1] = 0; // reverse ki shuruaat
            int k1start = 0; // forward diagonals ki range ghatane ke liye (start)
            int k1end = 0; // forward diagonals ki range ghatane ke liye (end)
            int k2start = 0; // reverse diagonals ki range ghatane ke liye (start)
            int k2end = 0; // reverse diagonals ki range ghatane ke liye (end)
            for (int d = 0; d <= maxD; d++) { // d = edits ki sankhya, 0 se badhao
                // ---- forward pass ----
                for (int k1 = -d + k1start; k1 <= d - k1end; k1 += 2) { // forward diagonals
                    int ko = off + k1; // array index
                    int x1; // x coordinate
                    if (k1 == -d || (k1 != d && v1[ko - 1] < v1[ko + 1])) { // upar se aana (insert) behtar?
                        x1 = v1[ko + 1]; // insert: x same
                    } else { // warna left se (delete)
                        x1 = v1[ko - 1] + 1; // delete: x + 1
                    }
                    int y1 = x1 - k1; // y nikalo
                    while (x1 < n && y1 < m && a[aLo + x1] == b[bLo + y1]) { // diagonal par same elements
                        x1++; // x aage
                        y1++; // y aage
                    }
                    v1[ko] = x1; // is diagonal ka sabse door ka x save
                    if (x1 > n) { // grid ke bahar nikal gaye
                        k1end += 2; // is side ki diagonal chhod do
                    } else if (y1 > m) { // grid ke bahar nikal gaye
                        k1start += 2; // is side ki diagonal chhod do
                    } else if (front) { // overlap check karna hai
                        int kk = delta - k1; // reverse ki matching diagonal
                        if (kk >= -maxD && kk <= maxD) { // array ki valid range me hai
                            int x2v = v2[off + kk]; // reverse ka x (end se doori)
                            if (x2v != -1 && x1 >= n - x2v) { // forward aur reverse mil gaye
                                solve(aLo, aLo + x1, bLo, bLo + y1); // left hissa solve
                                solve(aLo + x1, aHi, bLo + y1, bHi); // right hissa solve
                                return; // is range ka kaam khatam
                            }
                        }
                    }
                }
                // ---- reverse pass ----
                for (int k2 = -d + k2start; k2 <= d - k2end; k2 += 2) { // reverse diagonals
                    int ko = off + k2; // array index
                    int x2; // x coordinate (end se doori)
                    if (k2 == -d || (k2 != d && v2[ko - 1] < v2[ko + 1])) { // upar se aana behtar?
                        x2 = v2[ko + 1]; // x same
                    } else { // warna left se
                        x2 = v2[ko - 1] + 1; // x + 1
                    }
                    int y2 = x2 - k2; // y nikalo
                    while (x2 < n && y2 < m && a[aHi - x2 - 1] == b[bHi - y2 - 1]) { // end se same elements
                        x2++; // x aage
                        y2++; // y aage
                    }
                    v2[ko] = x2; // is diagonal ka best x save
                    if (x2 > n) { // grid ke bahar
                        k2end += 2; // diagonal chhod do
                    } else if (y2 > m) { // grid ke bahar
                        k2start += 2; // diagonal chhod do
                    } else if (!front) { // delta even hai to overlap reverse pass me milega
                        int kk = delta - k2; // forward ki matching diagonal
                        if (kk >= -maxD && kk <= maxD) { // valid range
                            int x1v = v1[off + kk]; // forward ka x
                            if (x1v != -1) { // agar wahan forward pahuncha tha
                                int y1v = x1v - kk; // forward ka y
                                if (x1v >= n - x2) { // forward aur reverse mil gaye
                                    solve(aLo, aLo + x1v, bLo, bLo + y1v); // left hissa solve
                                    solve(aLo + x1v, aHi, bLo + y1v, bHi); // right hissa solve
                                    return; // kaam khatam
                                }
                            }
                        }
                    }
                }
            }
            for (int i = aLo; i < aHi; i++) { // (kabhi nahi hona chahiye) safety: sab delete
                delA[i] = true; // delete mark
            }
            for (int j = bLo; j < bHi; j++) { // safety: sab insert
                insB[j] = true; // insert mark
            }
        }
    }

    // ---------------- output ke helpers ----------------
    // ek line likhta hai: prefix + line ke exact bytes + newline
    static void writeLine(OutputStream out, int prefix, Lines L, int i) throws IOException { // prefix, file, line number
        out.write(prefix); // prefix character (' ', '-', '+')
        out.write(L.data, L.start[i], L.end[i] - L.start[i]); // line ke bytes jaise ke taise
        out.write('\n'); // newline
    }

    // bytes ko Unicode code points me badalta hai (emoji = 1 character)
    static int[] codePoints(Lines L, int i) { // file aur line number
        String s = new String(L.data, L.start[i], L.end[i] - L.start[i], StandardCharsets.UTF_8); // bytes se string
        return s.codePoints().toArray(); // code points ka array
    }

    // true/false flags ko "3-5,9-12" jaisi ranges me badalta hai
    static String ranges(boolean[] f) { // changed flags
        StringBuilder sb = new StringBuilder(); // result
        int i = 0; // current position
        while (i < f.length) { // poori array par
            if (f[i]) { // changed character mila
                int j = i; // run ka end dhundhne ke liye
                while (j < f.length && f[j]) { // jab tak changed hain
                    j++; // aage badho
                }
                if (sb.length() > 0) { // agar pehle se range hai
                    sb.append(','); // comma lagao (space nahi)
                }
                sb.append(i).append('-').append(j); // start-end (end shamil nahi)
                i = j; // run ke baad se aage
            } else { // unchanged character
                i++; // aage badho
            }
        }
        return sb.length() == 0 ? "." : sb.toString(); // koi change nahi to "."
    }

    // ---------------- asli kaam ----------------
    static int run(String[] args) { // program ka main logic, exit code return karta hai
        if (args.length != 3 || !(args[0].equals("lines") || args[0].equals("highlight"))) { // galat arguments
            System.err.println("Usage: <program> lines|highlight fileA fileB"); // usage stderr par
            return 2; // error code
        }
        boolean highlight = args[0].equals("highlight"); // Part B mode hai ya nahi
        byte[] da; // file A ke bytes
        byte[] db; // file B ke bytes
        try { // files padhne ki koshish
            da = Files.readAllBytes(Paths.get(args[1])); // file A raw bytes me
            db = Files.readAllBytes(Paths.get(args[2])); // file B raw bytes me
        } catch (IOException | RuntimeException e) { // file padh nahi paye
            System.err.println("Error: cannot read file: " + e); // stderr par error message
            return 2; // stdout par kuch nahi, exit code 2
        }
        Lines A = split(da); // A ki lines
        Lines B = split(db); // B ki lines
        int[] idA = new int[A.n]; // A ki lines ke ids
        int[] idB = new int[B.n]; // B ki lines ke ids
        intern(A, B, idA, idB); // lines ko integer ids do
        int total = A.n + B.n; // ids ki range (0..total-1)
        int[] cntA = new int[total]; // har id A me kitni baar aayi
        int[] cntB = new int[total]; // har id B me kitni baar aayi
        for (int x = 0; x < A.n; x++) { // A ki har line
            cntA[idA[x]]++; // gino
        }
        for (int x = 0; x < B.n; x++) { // B ki har line
            cntB[idB[x]]++; // gino
        }
        int[] mapA = new int[A.n]; // A ki wo lines jo B me bhi hain (original index)
        int na = 0; // unki ginti
        for (int x = 0; x < A.n; x++) { // A ki har line
            if (cntB[idA[x]] > 0) { // agar ye line B me bhi hai
                mapA[na++] = x; // to Myers ke liye rakho
            }
        }
        int[] mapB = new int[B.n]; // B ki wo lines jo A me bhi hain (original index)
        int nb = 0; // unki ginti
        for (int x = 0; x < B.n; x++) { // B ki har line
            if (cntA[idB[x]] > 0) { // agar ye line A me bhi hai
                mapB[nb++] = x; // to Myers ke liye rakho
            }
        }
        int[] subA = new int[na]; // A ki filtered sequence (ids)
        for (int x = 0; x < na; x++) { // filtered A bharo
            subA[x] = idA[mapA[x]]; // id copy
        }
        int[] subB = new int[nb]; // B ki filtered sequence (ids)
        for (int x = 0; x < nb; x++) { // filtered B bharo
            subB[x] = idB[mapB[x]]; // id copy
        }
        Differ df = new Differ(subA, subB); // sirf common lines par differ (unique lines kabhi match nahi hoti)
        df.run(); // minimal diff nikalo
        boolean[] delA = new boolean[A.n]; // A ki kaun si lines delete hui (original index)
        boolean[] insB = new boolean[B.n]; // B ki kaun si lines insert hui (original index)
        Arrays.fill(delA, true); // jo line sirf A me thi wo delete hi hogi
        Arrays.fill(insB, true); // jo line sirf B me thi wo insert hi hogi
        for (int x = 0; x < na; x++) { // filtered A ke result wapas original index par
            delA[mapA[x]] = df.delA[x]; // Myers ka faisla copy
        }
        for (int x = 0; x < nb; x++) { // filtered B ke result wapas original index par
            insB[mapB[x]] = df.insB[x]; // Myers ka faisla copy
        }
        try { // output likhna
            OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16); // fast stdout
            int i = 0; // A ki current line
            int j = 0; // B ki current line
            while (i < A.n || j < B.n) { // jab tak dono files khatam na ho
                boolean change = (i < A.n && delA[i]) || (j < B.n && insB[j]); // change block shuru?
                if (change) { // agar change block hai
                    int i2 = i; // deleted lines ka end
                    while (i2 < A.n && delA[i2]) { // lagatar deleted lines
                        i2++; // aage badho
                    }
                    int j2 = j; // inserted lines ka end
                    while (j2 < B.n && insB[j2]) { // lagatar inserted lines
                        j2++; // aage badho
                    }
                    for (int p = i; p < i2; p++) { // pehle saari '-' lines (delete-first rule)
                        writeLine(out, '-', A, p); // delete line likho
                    }
                    for (int q = j; q < j2; q++) { // phir saari '+' lines
                        writeLine(out, '+', B, q); // insert line likho
                        if (highlight && (q - j) < (i2 - i)) { // Part B aur is + line ki pair '-' line hai
                            int[] oldCp = codePoints(A, i + (q - j)); // pair wali purani line ke characters
                            int[] newCp = codePoints(B, q); // nayi line ke characters
                            Differ cd = new Differ(oldCp, newCp); // character-level differ
                            cd.run(); // characters par Myers
                            String line = "? " + ranges(cd.delA) + " | " + ranges(cd.insB) + "\n"; // range wali line
                            out.write(line.getBytes(StandardCharsets.UTF_8)); // likho
                        }
                    }
                    i = i2; // deleted block ke aage
                    j = j2; // inserted block ke aage
                } else { // keep line (dono files me same)
                    writeLine(out, ' ', A, i); // space prefix ke saath likho
                    i++; // A aage
                    j++; // B aage
                }
            }
            out.flush(); // buffer ka bacha hua output likho
        } catch (IOException e) { // output error
            System.err.println("Error: cannot write output: " + e); // stderr par
            return 2; // error code
        }
        return 0; // sab theek
    }

    public static void main(String[] args) throws Exception { // program yahan se shuru
        Thread t = new Thread(null, () -> exitCode = run(args), "diff", 64L * 1024 * 1024); // bade stack wala thread (recursion ke liye)
        t.start(); // thread chalao
        t.join(); // khatam hone ka intezaar
        System.exit(exitCode); // sahi exit code ke saath band
    }
}