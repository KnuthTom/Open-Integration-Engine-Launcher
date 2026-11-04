/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// The process the Administrator runs in, on Windows. Task Manager names a process by its executable's
// FileDescription, and the Zulu runtime's javaw.exe says "Zulu Platform x64 Architecture" — so the
// launcher starts the client through this host instead, compiled into each bundled runtime's bin\ at
// package time (see brandClientRuntimes in app-fx/build.gradle.kts) with the assembly title below as
// its description. It does exactly what javaw.exe does: hand the command line to the runtime's own
// launcher library, jli.dll, which finds the JRE from this executable's location (…\bin\<this>.exe —
// the same place javaw.exe sits), reads jvm.cfg, loads jvm.dll and runs the main class in THIS process.
// No Azul binary is copied or altered. Built with the C# compiler every Windows carries (.NET
// Framework 4.x); a console variant is compiled with CONSOLE defined.
//
// VERSION, VENDOR and COPYRIGHT are filled in by the build.

using System;
using System.IO;
using System.Runtime.InteropServices;

[assembly: System.Reflection.AssemblyTitle("Open Integration Engine Administrator")]
[assembly: System.Reflection.AssemblyProduct("Open Integration Engine Administrator")]
[assembly: System.Reflection.AssemblyDescription("The Open Integration Engine / Mirth Connect Administrator, started by the Open Integration Engine Launcher")]
[assembly: System.Reflection.AssemblyCompany("@VENDOR@")]
[assembly: System.Reflection.AssemblyCopyright("@COPYRIGHT@")]
[assembly: System.Reflection.AssemblyVersion("@VERSION@")]
[assembly: System.Reflection.AssemblyFileVersion("@VERSION@")]
[assembly: System.Reflection.AssemblyInformationalVersion("@VERSION@")]

static class AdministratorHost
{
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool SetDllDirectory(string path);

    // int JLI_Launch(int argc, char** argv, int jargc, const char** jargv, int appclassc, const char** appclassv,
    //                const char* fullversion, const char* dotversion, const char* pname, const char* lname,
    //                jboolean javaargs, jboolean cpwildcard, jboolean javaw, jint ergo)
    [DllImport("jli.dll", CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Ansi)]
    static extern int JLI_Launch(
        int argc, string[] argv, int jargc, string[] jargv, int appclassc, string[] appclassv,
        string fullversion, string dotversion, string pname, string lname,
        [MarshalAs(UnmanagedType.U1)] bool javaargs, [MarshalAs(UnmanagedType.U1)] bool cpwildcard,
        [MarshalAs(UnmanagedType.U1)] bool javaw, int ergo);

    static int Main(string[] args)
    {
        string bin = AppDomain.CurrentDomain.BaseDirectory; // …\bin\ of the runtime this host was built into
        SetDllDirectory(bin);                               // jli.dll and what it loads come from here
        string[] argv = new string[args.Length + 1];
        argv[0] = Path.Combine(bin, "javaw.exe");           // the program name JLI reports in messages
        Array.Copy(args, 0, argv, 1, args.Length);
#if CONSOLE
        bool windowless = false;
#else
        bool windowless = true;
#endif
        try
        {
            return JLI_Launch(argv.Length, argv, 0, null, 0, null, "0", "0", "java", windowless ? "javaw" : "java", false, true, windowless, 0);
        }
        catch (DllNotFoundException e)
        {
            Report("The Java runtime beside this program is incomplete (jli.dll not found): " + e.Message, windowless);
            return 2;
        }
        catch (EntryPointNotFoundException e)
        {
            Report("The Java runtime beside this program is not one this host can start (JLI_Launch not found): " + e.Message, windowless);
            return 2;
        }
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int MessageBoxW(IntPtr owner, string text, string caption, uint type);

    static void Report(string text, bool windowless)
    {
        if (windowless) MessageBoxW(IntPtr.Zero, text, "Open Integration Engine Administrator", 0x10 /* MB_ICONERROR */);
        else Console.Error.WriteLine(text);
    }
}
