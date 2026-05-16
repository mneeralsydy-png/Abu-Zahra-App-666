#!/usr/bin/env python3
"""Proper daemon starter using double-fork."""
import os
import sys
import time

def daemonize():
    """Double-fork to create a proper daemon."""
    # First fork
    if os.fork() > 0:
        sys.exit(0)
    
    # Decouple from parent environment
    os.chdir('/')
    os.setsid()
    os.umask(0)
    
    # Second fork
    if os.fork() > 0:
        sys.exit(0)
    
    # Redirect standard file descriptors
    sys.stdout.flush()
    sys.stderr.flush()
    si = open(os.devnull, 'r')
    so = open(os.devnull, 'a+')
    se = open(os.devnull, 'a+')
    os.dup2(si.fileno(), sys.stdin.fileno())
    os.dup2(so.fileno(), sys.stdout.fileno())
    os.dup2(se.fileno(), sys.stderr.fileno())

if __name__ == '__main__':
    daemonize()
    
    # Now import and run the bot
    import asyncio
    import bot_module
    
    # Redirect output to log file
    log_path = '/home/z/my-project/bot.log'
    sys.stdout = open(log_path, 'a')
    sys.stderr = open(log_path, 'a')
    
    asyncio.run(bot_module._run())
