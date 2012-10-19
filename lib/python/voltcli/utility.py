# This file is part of VoltDB.

# Copyright (C) 2008-2012 VoltDB Inc.
#
# This file contains original code and/or modifications of original code.
# Any modifications made by VoltDB Inc. are licensed under the following
# terms and conditions:
#
# Permission is hereby granted, free of charge, to any person obtaining
# a copy of this software and associated documentation files (the
# "Software"), to deal in the Software without restriction, including
# without limitation the rights to use, copy, modify, merge, publish,
# distribute, sublicense, and/or sell copies of the Software, and to
# permit persons to whom the Software is furnished to do so, subject to
# the following conditions:
#
# The above copyright notice and this permission notice shall be
# included in all copies or substantial portions of the Software.

# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
# EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
# MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
# IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
# OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
# ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
# OTHER DEALINGS IN THE SOFTWARE.

# Volt CLI utility functions.

# IMPORTANT: This depends on no other voltcli modules. Please keep it that way.

import sys
import os
import subprocess
import glob
import copy
import inspect
import ConfigParser
import zipfile
import re
import pkgutil
import binascii
from xml.etree import ElementTree

__author__ = 'scooper'

#===============================================================================
class Global:
#===============================================================================
    """
    Global data for utilities.
    """
    verbose_enabled = False
    debug_enabled   = False
    dryrun_enabled  = False
    manifest_path   = 'MANIFEST'

#===============================================================================
def set_dryrun(dryrun):
#===============================================================================
    """
    Enable or disable command dry run (display only/no execution).
    """
    Global.dryrun_enabled = dryrun

#===============================================================================
def set_verbose(verbose):
#===============================================================================
    """
    Enable or disable verbose messages. Increases the number of INFO messages.
    """
    Global.verbose_enabled = verbose

#===============================================================================
def set_debug(debug):
#===============================================================================
    """
    Enable or disable DEBUG messages. Also enables verbose INFO messages.
    """
    Global.debug_enabled = debug
    if debug:
        Global.verbose_enabled = True

#===============================================================================
def is_dryrun():
#===============================================================================
    """
    Return True if dry-run is enabled.
    """
    return Global.dryrun_enabled

#===============================================================================
def is_verbose():
#===============================================================================
    """
    Return True if verbose messages are enabled.
    """
    return Global.verbose_enabled

#===============================================================================
def is_debug():
#===============================================================================
    """
    Return True if debug messages are enabled.
    """
    return Global.debug_enabled

#===============================================================================
def display_messages(msgs, f = sys.stdout, tag = None, level = 0):
#===============================================================================
    """
    Low level message display.
    """
    if tag:
        stag = '%8s: ' % tag
    else:
        stag = ''
    # Special case to allow a string instead of an iterable.
    try:
        # Raises TypeError if not string
        var = msgs + ' '
        msgs = [msgs]
    except TypeError:
        pass
    sindent = level * '  '
    # Recursively process message list and sub-lists.
    for msg in msgs:
        if msg is not None:
            # Handle exceptions
            if issubclass(msg.__class__, Exception):
                f.write('%s%s%s Exception: %s\n' % (stag, sindent, msg.__class__.__name__, str(msg)))
            else:
                # Handle multi-line strings
                try:
                    # Raises TypeError if not string
                    var = msg + ' '
                    # If it is a string slice and dice it by linefeeds.
                    for msg2 in msg.split('\n'):
                        f.write('%s%s%s\n' % (stag, sindent, msg2))
                except TypeError:
                    # Recursively display an iterable with indentation added.
                    if hasattr(msg, '__iter__'):
                        display_messages(msg, f = f, tag = tag, level = level + 1)
                    else:
                        f.write('%s%s%s\n' % (stag, sindent, str(msg)))

#===============================================================================
def info(*msgs):
#===============================================================================
    """
    Display INFO level messages.
    """
    display_messages(msgs, tag = 'INFO')

#===============================================================================
def verbose_info(*msgs):
#===============================================================================
    """
    Display verbose INFO level messages if enabled.
    """
    if Global.verbose_enabled:
        display_messages(msgs, tag = 'INFO2')

#===============================================================================
def debug(*msgs):
#===============================================================================
    """
    Display DEBUG level message(s) if debug is enabled.
    """
    if Global.debug_enabled:
        display_messages(msgs, tag = 'DEBUG')

#===============================================================================
def warning(*msgs):
#===============================================================================
    """
    Display WARNING level messages.
    """
    display_messages(msgs, tag = 'WARNING')

#===============================================================================
def error(*msgs):
#===============================================================================
    """
    Display ERROR level messages.
    """
    display_messages(msgs, tag = 'ERROR')

#===============================================================================
def abort(*msgs):
#===============================================================================
    """
    Display ERROR messages and then abort.
    """
    error(*msgs)
    display_messages('Exiting.', f = sys.stderr, tag = 'FATAL')
    sys.exit(1)

#===============================================================================
def find_in_path(name):
#===============================================================================
    """
    Find program in the system path.
    """
    # NB: non-portable
    for dir in os.environ['PATH'].split(':'):
        if os.path.exists(os.path.join(dir, name)):
            return os.path.join(dir, name)
    return None

#===============================================================================
class PythonSourceFinder(object):
#===============================================================================
    """
    Find and invoke python source files in a set of directories and resource
    subdirectories (for searching in zip packages).  Execute all discovered
    source files and pass in the symbols provided.
    A typical usage relies on decorators to mark discoverable functions in user
    code. The decorator is called when the source file is executed which serves
    as an opportunity to keep track of discovered functions.
    """
    class Scan(object):
        def __init__(self, package, path):
            self.package = package
            self.path    = path

    def __init__(self):
        self.scan_locs = []
        self.manifests = {}

    def add_path(self, path):
        # Use the absolute path to avoid visiting the same directory more than once.
        full_path = os.path.realpath(path)
        for scan_loc in self.scan_locs:
            if scan_loc.path == full_path:
                break
        else:
            self.scan_locs.append(PythonSourceFinder.Scan(None, full_path))

    def add_resource(self, package, path):
        self.scan_locs.append(PythonSourceFinder.Scan(package, path))

    def search_and_execute(self, **syms):
        for scan_loc in self.scan_locs:
            if scan_loc.package:
                # Load the manifest as needed so that individual files can be
                # found in package directories. There doesn't seem to be an
                # easy way to search for resource files, e.g. by glob pattern.
                if scan_loc.package not in self.manifests:
                    try:
                        manifest_raw = pkgutil.get_data(scan_loc.package, Global.manifest_path)
                        self.manifests[scan_loc.package] = manifest_raw.split('\n')
                    except (IOError, OSError), e:
                        abort('Failed to load package %s.' % Global.manifest_path, e)
                for path in self.manifests[scan_loc.package]:
                    if os.path.dirname(path) == scan_loc.path and path.endswith('.py'):
                        debug('Executing package module "%s"...' % path)
                        try:
                            code = pkgutil.get_data(scan_loc.package, path)
                        except (IOError, OSError), e:
                            abort('Failed to load package resource "%s".' % path, e)
                        syms_tmp = copy.copy(syms)
                        exec(code, syms_tmp)
            elif os.path.exists(scan_loc.path):
                for modpath in glob.glob(os.path.join(scan_loc.path, '*.py')):
                    debug('Executing module "%s"...' % modpath)
                    syms_tmp = copy.copy(syms)
                    execfile(modpath, syms_tmp)

#===============================================================================
def normalize_list(items, width, filler = None):
#===============================================================================
    """
    Normalize list to a specified width, truncating or filling as needed.
    Filler data can be supplied by caller. The filler will be copied to each
    added item. None will be used as the filler if none is provided.
    """
    assert items is not None
    assert width >= 0
    output = []
    for item in items:
        if len(output) == width:
            break
        output.append(item)
    if len(output) < width:
        output += filler * (width - len(output))
    return tuple(output)

#===============================================================================
def format_table(caption, headings, data_rows):
#===============================================================================
    """
    Format a tabular display including an optional caption, optional column
    headings, and rows of data cells. Aligns the headings and data cells.
    Headings and data rows must be iterable. Each data row must provide
    iterable cells.  For now it only handles stringized data and right
    alignment. Returns the table-formatted string.
    """
    output = []
    # Display the caption, if supplied.
    if caption:
        output.append('-- %s --\n' % caption)
    # Add a row for headings, if supplied.
    rows = []
    if headings:
        rows[0] = heading_row = []
        for heading in headings:
            heading_row.append('- %s -' % heading)
    rows.extend(data_rows)
    # Measure the column widths.
    widths = []
    for row in rows:
        icolumn = 0
        for column in row:
            width = len(str(column))
            if len(widths) == icolumn:
                widths.append(width)
            else:
                widths[icolumn] = max(widths[icolumn], width)
            icolumn += 1
    # Generate the format string and then format the headings and rows.
    fmt = '  '.join(['%%-%ds' % width for width in widths])
    for row in rows:
        output.append(fmt % normalize_list(row, len(widths), ''))
    return '\n'.join(output)

#===============================================================================
def parse_xml(xml_path):
#===============================================================================
    """
    Parses XML and returns an ElementTree object to provide access to element data.
    """
    et = ElementTree.ElementTree()
    try:
        return et.parse(xml_path)
    except (OSError, IOError), e:
        abort('Failed to parse XML file.', (xml_path, e))

#===============================================================================
def run_cmd(cmd, *args):
#===============================================================================
    """
    Run external program without capturing or suppressing output and check return code.
    """
    fullcmd = cmd
    for arg in args:
        sarg = str(arg)
        if len(sarg.split()) > 1:
            fullcmd += ' "%s"' % sarg
        else:
            fullcmd += ' %s' % sarg
    if Global.dryrun_enabled:
        sys.stdout.write('%s\n' % fullcmd)
    else:
        if Global.verbose_enabled:
            verbose_info('Run: %s' % fullcmd)
        retcode = os.system(fullcmd)
        if retcode != 0:
            abort('Command "%s ..." failed with return code %d.' % (cmd, retcode))

#===============================================================================
def pipe_cmd(*args):
#===============================================================================
    """
    Run an external program, capture its output, and yield each output line for
    iteration.
    """
    try:
        proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for line in iter(proc.stdout.readline, ''):
            yield line.rstrip()
        proc.stdout.close()
    except Exception, e:
        warning('Exception running command: %s' % ' '.join(args), e)

#===============================================================================
def is_string(item):
#===============================================================================
    """
    Return True if the item behaves like a string.
    """
    try:
        test_string = item + ''
        return True
    except TypeError:
        return False

#===============================================================================
def is_sequence(item):
#===============================================================================
    """
    Return True if the item behaves like an iterable sequence.
    """
    if is_string(item):
        return False
    try:
        for var in item:
            break
        return True
    except TypeError:
        return False

#===============================================================================
def _flatten(*items):
#===============================================================================
    """
    Internal function to recursively iterate a potentially nested sequence.
    None items are filtered out.
    """
    for item in items:
        if is_sequence(item):
            for subitem in item:
                for subsubitem in _flatten(subitem):
                    if subitem is not None:
                        yield subsubitem
        else:
            if item is not None:
                yield item

#===============================================================================
def flatten(*items):
#===============================================================================
    """
    Flatten and yield individual items from a potentially nested list or tuple.
    """
    for item in _flatten(*items):
        yield item

#===============================================================================
def flatten_to_list(*items):
#===============================================================================
    """
    Flatten a potentially nested list or tuple to a simple list.
    """
    return [item for item in flatten(*items)]

#===============================================================================
def to_display_string(item):
#===============================================================================
    """
    Recursively convert simple items and potentially nested sequences to a
    string, using square brackets and commas to format sequences.
    """
    if not is_sequence(item):
        return str(item)
    s = ''
    for subitem in item:
        if s:
            s += ', '
        s += to_display_string(subitem)
    return '[%s]' % s

#===============================================================================
class Zipper(object):
#===============================================================================
    """
    The Zipper class creates a zip file using the directories, strings, and
    exclusion regular expresions provided.
    """
    def __init__(self, *excludes):
        self.output_zip  = None
        self.output_path = None
        self.manifest    = []
        self.re_excludes = [re.compile(exclude) for exclude in excludes]

    def open(self, output_path):
        self.output_path = output_path
        if not Global.dryrun_enabled:
            try:
                self.output_zip = zipfile.ZipFile(self.output_path, 'w', zipfile.ZIP_DEFLATED)
            except (IOError, OSError), e:
                self._abort('Failed to open for writing.', e)

    def close(self):
        if self.output_zip:
            # Write the manifest.
            try:
                self.output_zip.writestr(Global.manifest_path, '\n'.join(self.manifest))
            except (IOError, OSError), e:
                self._abort('Failed to write %s.' % Global.manifest_path, e)
            self.output_zip.close()

    def add_file(self, path_in, path_out):
        for re_exclude in self.re_excludes:
            path_in_full = os.path.realpath(path_in)
            if re_exclude.search(path_in):
                self._debug('skip "%s"' % path_in_full)
                break
        else:
            self._debug('add "%s" as "%s"' % (path_in_full, path_out))
            try:
                if self.output_zip:
                    self.output_zip.write(path_in, path_out)
                    self.manifest.append(path_out)
            except (IOError, OSError), e:
                self._abort('Failed to write file "%s" to output zip file "%s".', path_out, e)

    def add_string(self, s, path_out):
        self._debug('write string to "%s"' % path_out)
        if self.output_zip:
            try:
                self.output_zip.writestr(path_out, s)
                self.manifest.append(path_out)
            except (IOError, OSError), e:
                self._abort('Failed to write string to file "%s".' % path_out, e)

    def add_directory(self, path_in, dst, excludes = []):
        if not os.path.isdir(path_in):
            self._abort('Zip source directory "%s" does not exist.' % path_in)
        savedir = os.getcwd()
        # Get nice relative paths by temporarily switching directories.
        os.chdir(path_in)
        try:
            for basedir, subdirs, filenames in os.walk('.'):
                for filename in filenames:
                    file_path_in = os.path.join(basedir, filename)[2:]
                    file_path_out = os.path.join(dst, basedir[2:], filename)
                    self.add_file(file_path_in, file_path_out)
        finally:
            os.chdir(savedir)

    def _debug(self, msg):
        debug('%s: %s' % (self.output_path, msg))

    def _abort(self, *msgs):
        abort('Fatal error writing zip file "%s".' % self.output_path, msgs)

#===============================================================================
def merge_java_options(*opts):
#===============================================================================
    """
    Merge redundant -X... java command line options. Keep others intact.
    Arguments can be lists or individual arguments. Returns the reduced list.
    """
    ret_opts = []
    xargs = set()
    for opt in flatten(*opts):
        if opt is not None:
            # This is somewhat simplistic logic that might have unlikely failure scenarios.
            if opt.startswith('-X'):
                # The symbol is the initial string of contiguous alphabetic characters.
                sym = ''.join([c for c in opt[2:] if c.isalpha()])
                if sym not in xargs:
                    xargs.add(sym)
                    ret_opts.append(opt)
            else:
                ret_opts.append(opt)
    return ret_opts

#===============================================================================
def kwargs_merge_list(kwargs, name, *args):
#===============================================================================
    """
    Merge and flatten kwargs list with additional items.
    """
    kwargs[name] = flatten_to_list(kwargs.get(name, None), *args)

#===============================================================================
def kwargs_merge_java_options(kwargs, name, *args):
#===============================================================================
    """
    Merge and flatten kwargs Java options list with additional options.
    """
    kwargs[name] = merge_java_options(kwargs.get(name, None), *args)

#===============================================================================
def choose(prompt, *choices):
#===============================================================================
    """
    Prompt the user for multiple choice input. Keep prompting until a valid
    choice is received. Choice shortcuts require unique first letters. The user
    can either respond with a single letter or an entire word.
    """
    letters = set()
    choice_list = []
    for choice in choices:
        if not choice:
            abort('Empty choice passed to choose().')
        if choice[0] in letters:
            abort('Non-unique choices %s passed to choose().' % str(choices))
        letters.add(choice[0])
        choice_list.append('[%s]%s' % (choice[0], choice[1:]))
    while True:
        sys.stdout.write('%s (%s) ' % (prompt, '/'.join(choice_list)))
        sys.stdout.flush()
        response = sys.stdin.readline().strip()
        if response in letters or response in choices:
            return response[0]

#===============================================================================
def parse_hosts(host_string, min_hosts = None, max_hosts = None, default_port = None):
#===============================================================================
    """
    Split host string on commas, extract optional port for each and return list
    of host objects. Check against minimum/maximum quantities if specified.
    """
    class Host(object):
        def __init__(self, host, port):
            self.host = host
            self.port = port
    hosts = []
    for host_port in host_string.split(','):
        split_host = host_port.split(':')
        host = split_host[0]
        if len(split_host) > 2:
            abort('Bad HOST:PORT format "%s" - too many colons.' % host_port)
        if len(split_host) == 1:
            # Add the default port if specified.
            if default_port:
                port = default_port
            else:
                port = None
        else:
            try:
                port = int(split_host[1])
            except ValueError, e:
                abort('Bad port value "%s".' % split_host[1], e)
        hosts.append(Host(host, port))
    if min_hosts is not None and len(hosts) < min_hosts:
        abort('Too few hosts in host string "%s". The minimum is %d.'
                    % (host_string, min_hosts))
    if max_hosts is not None and len(hosts) < max_hosts:
        abort('Too many hosts in host string "%s". The maximum is %d.'
                    % (host_string, max_hosts))
    return hosts

#===============================================================================
class File(object):
#===============================================================================
    """
    File reader/writer object that aborts on any error. Must explicitly call
    close(). The main point is to standardize the error-handling.
    """
    def __init__(self, path, mode = 'r'):
        if mode not in ('r', 'w'):
            abort('Invalid file mode "%s".' % mode)
        self.path = path
        self.mode = mode
        self.f    = None
    def open(self):
        self.close()
        self.f = self._open()
    def read(self):
        if self.mode != 'r':
            self._abort('File is not open for reading in call to read().')
        # Reading the entire file, so we can automatically open and close here.
        if self.f is None:
            f = self._open()
        else:
            f = self.f
        try:
            try:
                return f.read()
            except (IOError, OSError), e:
                abort('Read error.', e)
        finally:
            # Close locally-opened file.
            if self.f is None:
                f.close()
    def read_hex(self):
        return binascii.hexlify(self.read())
    def write(self, s):
        if self.mode != 'w':
            self._abort('File is not open for writing in call to write().')
        if self.f is None:
            self._abort('File was not opened in call to write().')
        try:
            self.f.write(s)
        except (IOError, OSError), e:
            abort('Write error.', e)
    def close(self):
        if self.f:
            self.f.close()
    def _open(self):
        try:
            return open(self.path, self.mode)
        except (IOError, OSError), e:
            self._abort('File open error.', e)
    def _abort(self, msg, e = None):
        msgs = ['''File("%s",'%s'): %s''' % (self.path, self.mode, msg)]
        if e:
            msgs.append(str(e))
        abort(*msgs)

#===============================================================================
class XMLConfigManager(object):
#===============================================================================
    """
    Loads/saves XML format configuration to and from a dictionary.
    """

    def load(self, path):
        parser = ConfigParser.SafeConfigParser()
        parser.read(path)
        d = dict()
        for section in parser.sections():
            for name, value in parser.items(section):
                d['%s.%s' % (section, name)] = value
        return d

    def save(self, path, d):
        parser = ConfigParser.SafeConfigParser()
        keys = d.keys()
        keys.sort()
        cur_section = None
        for key in keys:
            section, name = key.split('.', 1)
            if cur_section is None or section != cur_section:
                parser.add_section(section)
                cur_section = section
            parser.set(cur_section, name, d[name])
            f = FileWriter(path)
            try:
                parser.write(f)
            finally:
                f.close()

#===============================================================================
class INIConfigManager(object):
#===============================================================================
    """
    Loads/saves INI format configuration to and from a dictionary.
    """

    def load(self, path):
        parser = ConfigParser.SafeConfigParser()
        parser.read(path)
        d = dict()
        for section in parser.sections():
            for name, value in parser.items(section):
                d['%s.%s' % (section, name)] = value
        return d

    def save(self, path, d):
        parser = ConfigParser.SafeConfigParser()
        keys = d.keys()
        keys.sort()
        cur_section = None
        for key in keys:
            if key.find('.') == -1:
                abort('Key "%s" must have a section, e.g. "volt.%s"' % (key, key))
            else:
                section, name = key.split('.', 1)
            if cur_section is None or section != cur_section:
                parser.add_section(section)
                cur_section = section
            parser.set(cur_section, name, d[key])
        f = FileWriter(path)
        try:
            parser.write(f)
        finally:
            f.close()

#===============================================================================
class PersistentConfig(object):
#===============================================================================
    """
    Persistent access to configuration data. Manages two configuration
    files, one for permanent configuration and the other for local state.
    """

    def __init__(self, format, permanent_path, local_path):
        """
        Construct persistent configuration based on specified format name, path
        to permanent config file, and path to local config file.
        """
        self.permanent_path = permanent_path
        self.local_path     = local_path
        if format.lower() == 'ini':
            self.config_manager = INIConfigManager()
        else:
            abort('Unsupported configuration format "%s".' % format)
        self.permanent = self.config_manager.load(self.permanent_path)
        self.local     = self.config_manager.load(self.local_path)

    def save_permanent(self):
        """
        Save the permanent configuration.
        """
        self.config_manager.save(self.permanent_path, self.permanent)

    def save_local(self):
        """
        Save the local configuration (overrides and additions to permanent).
        """
        self.config_manager.save(self.local_path, self.local)

    def get(self, key):
        """
        Get a value for a key from the merged configuration.
        """
        if key in self.local:
            return self.local[key]
        return self.permanent.get(key, None)

    def set_permanent(self, key, value):
        """
        Set a key/value pair in the permanent configuration.
        """
        self.permanent[key] = value
        self.save_permanent()

    def set_local(self, key, value):
        """
        Set a key/value pair in the local configuration.
        """
        self.local[key] = value
        self.save_local()

    def query(self, filter = filter):
        """
        Query for keys and values as a merged dictionary.
        The optional filter is matched against the start of each key.
        """
        if filter:
            results = {}
            for key in self.local:
                if key.startswith(filter):
                    results[key] = self.local[key]
            for key in self.permanent:
                if key not in results and key.startswith(filter):
                    self.results[key] = self.permanent[key]
        else:
            results = self.local
            for key in self.permanent:
                if key not in results:
                    self.results[key] = self.permanent[key]
        return results

    def query_pairs(self, filter = None):
        """
        Query for keys and values as a sorted list of (key, value) pairs.
        The optional filter is matched against the start of each key.
        """
        d = self.query(filter = filter)
        keys = d.keys()
        keys.sort()
        results = []
        for key in keys:
            results.append((key, d[key]))
        return results
